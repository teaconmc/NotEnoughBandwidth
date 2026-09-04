package org.teacon.neb.network.chunk.preshare.repo;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.teacon.neb.network.aggregate.compress.CompressContext;
import org.teacon.neb.network.chunk.preshare.data.BlockEntityInfo;
import org.teacon.neb.network.chunk.preshare.data.HeightMap;
import org.teacon.neb.network.chunk.preshare.data.LevelLightSection;
import org.teacon.neb.network.chunk.preshare.data.PresharedChunk;
import org.teacon.neb.network.chunk.preshare.data.SectionInstance;
import org.teacon.neb.network.chunk.preshare.repo.impl.PresharedChunkLocalSource;
import org.teacon.neb.utils.ContextByteBuf;
import org.teacon.neb.utils.GridPos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.teacon.neb.utils.GridPos.GRID_SIZE;

public final class PresharedChunksIO {
    private static final Logger LOGGER = LoggerFactory.getLogger(PresharedChunksIO.class);

    private PresharedChunksIO() {
    }

    public static List<PresharedChunk> read(GridPos grid, ContextByteBuf raw) {
        ContextByteBuf buffer;
        try (CompressContext context = CompressContext.ofPresharedChunk()) {
            buffer = new ContextByteBuf(context.decompress(raw), raw.registryAccess(), ConnectionType.NEOFORGE);
        }
        try {
            List<PresharedChunk> value = new ArrayList<>(GRID_SIZE * GRID_SIZE);
            GridPos actualGrid = GridPos.STREAM_CODEC.decode(buffer);
            if (!grid.equals(actualGrid)) {
                throw new IllegalArgumentException(String.format("Cannot deserialize grid file at %s: Unexpecting %s", grid, actualGrid));
            }

            for (GridIndexer indexer : GridIndexer.of(grid)) {
                HeightMap heightmaps = HeightMap.STREAM_CODEC.decode(buffer);
                List<SectionInstance> sections = SectionInstance.STREAM_CODEC.decode(buffer);
                List<LevelLightSection> lights = LevelLightSection.STREAM_CODEC.decode(buffer);
                Int2ObjectMap<BlockEntityInfo> blockEntities = BlockEntityInfo.BLOCK_CODEC.decode(buffer);
                value.add(new PresharedChunk(indexer.chunkPos(), heightmaps, sections, lights, blockEntities));
            }

            return value;
        } finally {
            buffer.release();
        }
    }

    public static CompletableFuture<Void> save(Path directory, LongIterator chunks) {
        MinecraftServer server = Objects.requireNonNull(ServerLifecycleHooks.getCurrentServer());

        CompletableFuture<Void> future = new CompletableFuture<>();
        ServerChunkCache chunkSource = server.overworld().getChunkSource();
        LongSet scheduled = new LongOpenHashSet();
        ThreadPoolExecutor executor = ofExecutorService("Server Chunk Compressor [Native]");

        Thread poller = new Thread(() -> pollTasks(directory, chunks, scheduled, chunkSource, executor, server, future));
        poller.setName("Server Chunk Poller");
        poller.setDaemon(true);
        poller.setUncaughtExceptionHandler((_, t) -> {
            if (t != null) {
                future.completeExceptionally(t);
            }
        });
        poller.start();
        return future;
    }

    public static ThreadPoolExecutor ofExecutorService(String name) {
        int count = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
                count, count,
                30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                Thread.ofPlatform().name("NEB " + name).daemon().factory()
        );
    }

    private static void pollTasks(
            Path directory,
            LongIterator chunks,
            LongSet scheduled,
            ServerChunkCache chunkSource,
            ThreadPoolExecutor executor,
            MinecraftServer server,
            CompletableFuture<Void> future
    ) {
        int permits = executor.getMaximumPoolSize() + 4;
        Semaphore semaphore = new Semaphore(permits);
        while (chunks.hasNext()) {
            GridPos grid = GridPos.fromChunk(ChunkPos.unpack(chunks.nextLong()));
            if (!scheduled.add(grid.pack())) {
                continue;
            }

            semaphore.acquireUninterruptibly();
            CompletableFuture.completedFuture(new PresharedChunk[GRID_SIZE * GRID_SIZE])
                    .thenComposeAsync(values -> {
                        TicketType ticketType = new TicketType(0L, 15);
                        for (GridIndexer indexer : GridIndexer.of(grid)) {
                            chunkSource.addTicket(new Ticket(ticketType, 33), indexer.chunkPos());
                        }
                        ChunkSourceAccess.runDistanceManagerUpdates(chunkSource);

                        CompletableFuture<?>[] futures = new CompletableFuture[values.length];
                        for (GridIndexer indexer : GridIndexer.of(grid)) {
                            int index = indexer.index(), chunkX = indexer.chunkX(), chunkZ = indexer.chunkZ();

                            futures[index] = CompletableFuture.completedFuture(null)
                                    .thenComposeAsync(
                                            _ -> ChunkSourceAccess.getChunkFutureMainThread(chunkSource, chunkX, chunkZ, ChunkStatus.SPAWN, false),
                                            server
                                    )
                                    .thenAccept(chunk -> {
                                        if (chunk.getError() != null) {
                                            throw new IllegalArgumentException(String.format("Cannot load chunk at %d, %d: %s", chunkX, chunkX, chunk.getError()));
                                        }

                                        LevelChunk loaded = switch (chunk.orElseThrow(AssertionError::new)) {
                                            case LevelChunk c -> c;
                                            case ImposterProtoChunk c -> c.getWrapped();
                                            case ProtoChunk proto -> {
                                                ServerLevel level = chunkSource.level;
                                                yield new LevelChunk(level, proto, _ -> {
                                                    try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(proto.problemPath(), LOGGER)) {
                                                        ValueInput.ValueInputList entities = TagValueInput.create(reporter, level.registryAccess(), proto.getEntities());
                                                        if (!entities.isEmpty()) {
                                                            level.addWorldGenChunkEntities(EntityType.loadEntitiesRecursive(entities, level, EntitySpawnReason.LOAD));
                                                        }
                                                    }
                                                });
                                            }
                                            default -> throw new UnsupportedOperationException();
                                        };

                                        loaded.runPostLoad();
                                        values[index] = PresharedChunk.createCache(loaded);
                                    });
                        }

                        return CompletableFuture.allOf(futures).thenApply(_ -> values)
                                .whenCompleteAsync((_, _) -> {
                                    for (GridIndexer indexer : GridIndexer.of(grid)) {
                                        chunkSource.removeTicketWithRadius(ticketType, indexer.chunkPos(), 0);
                                    }

                                    ChunkSourceAccess.runDistanceManagerUpdates(chunkSource);
                                    ChunkSourceAccess.processUnloads(chunkSource);
                                }, server);
                    }, server)
                    .thenAcceptAsync(values -> {
                        write(directory.resolve(PresharedChunkLocalSource.getName(grid.pack())), grid.pack(), Arrays.asList(values), server.registryAccess());
                    }, executor)
                    .whenComplete((_, t) -> {
                        semaphore.release();
                        if (t != null) {
                            LOGGER.error("Cannot create preshared chunks for grid: {}", GridPos.unpack(grid.pack()), t);
                            future.completeExceptionally(t);
                        }
                    });
        }

        semaphore.acquireUninterruptibly(permits);
        executor.shutdown();
        try {
            Files.writeString(PresharedChunkLocalSource.resolveIndex(directory), UUID.randomUUID().toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Cannot write index file.", e);
        }

        try {
            if (!executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                throw new AssertionError();
            }
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }

        LOGGER.info("Done. All requested files have been saved to {}", directory);
        future.complete(null);
    }

    private static void write(Path resolve, long gridXZ, List<PresharedChunk> value, RegistryAccess registryAccess) {
        ContextByteBuf buffer = new ContextByteBuf(Unpooled.directBuffer(), registryAccess, ConnectionType.NEOFORGE);
        try {
            GridPos.STREAM_CODEC.encode(buffer, GridPos.unpack(gridXZ));

            for (GridIndexer indexer : GridIndexer.of(gridXZ)) {
                PresharedChunk chunk = value.get(indexer.index());

                HeightMap.STREAM_CODEC.encode(buffer, chunk.heightmaps());
                SectionInstance.STREAM_CODEC.encode(buffer, chunk.sections());
                LevelLightSection.STREAM_CODEC.encode(buffer, chunk.lights());
                BlockEntityInfo.BLOCK_CODEC.encode(buffer, chunk.blockEntities());
            }

            ByteBuf compressed = buffer.alloc().directBuffer();
            try (CompressContext context = CompressContext.ofPresharedChunk()) {
                context.compress(buffer, compressed);
            }

            try (OutputStream os = Files.newOutputStream(resolve); InputStream is = new ByteBufInputStream(compressed)) {
                is.transferTo(os);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                compressed.release();
            }
        } finally {
            buffer.release();
        }
    }
}
