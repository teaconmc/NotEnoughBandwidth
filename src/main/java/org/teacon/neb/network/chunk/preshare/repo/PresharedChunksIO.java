package org.teacon.neb.network.chunk.preshare.repo;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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
import org.teacon.neb.utils.vm.LookupAccess;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
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
import java.util.function.BooleanSupplier;

import static org.teacon.neb.utils.GridPos.GRID_SIZE;

public final class PresharedChunksIO {
    private static final Logger LOGGER = LoggerFactory.getLogger(PresharedChunksIO.class);

    private static final MethodHandle RUN_DISTANCE_MANAGER_UPDATES;
    private static final MethodHandle GET_CHUNK_FUTURE_MAIN_THREAD;
    private static final MethodHandle PROCESS_UNLOAD;

    static {
        try {
            RUN_DISTANCE_MANAGER_UPDATES = LookupAccess.IMPL_LOOKUP.findVirtual(
                    ServerChunkCache.class, "runDistanceManagerUpdates", MethodType.methodType(boolean.class)
            );

            GET_CHUNK_FUTURE_MAIN_THREAD = LookupAccess.IMPL_LOOKUP.findVirtual(
                    ServerChunkCache.class, "getChunkFutureMainThread", MethodType.methodType(CompletableFuture.class, int.class, int.class, ChunkStatus.class, boolean.class)
            );

            PROCESS_UNLOAD = LookupAccess.IMPL_LOOKUP.findVirtual(
                    ChunkMap.class, "processUnloads", MethodType.methodType(void.class, BooleanSupplier.class)
            );
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private PresharedChunksIO() {
    }

    public static List<PresharedChunk> read(ContextByteBuf raw) {
        ContextByteBuf buffer;
        try (CompressContext context = CompressContext.ofPresharedChunk()) {
            buffer = new ContextByteBuf(context.decompress(raw), raw.registryAccess(), ConnectionType.NEOFORGE);
        }
        try {
            List<PresharedChunk> value = new ArrayList<>(GRID_SIZE * GRID_SIZE);
            GridPos grid = GridPos.STREAM_CODEC.decode(buffer);

            for (int dx = 0; dx < GRID_SIZE; dx++) {
                for (int dz = 0; dz < GRID_SIZE; dz++) {
                    HeightMap heightmaps = HeightMap.STREAM_CODEC.decode(buffer);
                    List<SectionInstance> sections = SectionInstance.STREAM_CODEC.decode(buffer);
                    List<LevelLightSection> lights = LevelLightSection.STREAM_CODEC.decode(buffer);
                    Int2ObjectMap<BlockEntityInfo> blockEntities = BlockEntityInfo.BLOCK_CODEC.decode(buffer);
                    value.add(new PresharedChunk(new ChunkPos(grid.x() * GRID_SIZE + dx, grid.z() * GRID_SIZE + dz), heightmaps, sections, lights, blockEntities));
                }
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

                        for (int dx = 0; dx < GRID_SIZE; dx++) {
                            for (int dz = 0; dz < GRID_SIZE; dz++) {
                                int chunkX = grid.x() * GRID_SIZE + dx, chunkZ = grid.z() * GRID_SIZE + dz;
                                chunkSource.addTicket(new Ticket(ticketType, 33), new ChunkPos(chunkX, chunkZ));
                            }
                        }
                        try {
                            boolean _ = (boolean) RUN_DISTANCE_MANAGER_UPDATES.invokeExact(chunkSource);
                        } catch (Throwable t) {
                            throw LookupAccess.raise(t);
                        }

                        CompletableFuture<?>[] futures = new CompletableFuture[values.length];
                        for (int dx = 0; dx < GRID_SIZE; dx++) {
                            for (int dz = 0; dz < GRID_SIZE; dz++) {
                                int index = dx * GRID_SIZE + dz;
                                int chunkX = grid.x() * GRID_SIZE + dx, chunkZ = grid.z() * GRID_SIZE + dz;

                                futures[index] = CompletableFuture.completedFuture(null)
                                        .thenComposeAsync(_ -> {
                                            try {
                                                // noinspection unchecked
                                                return (CompletableFuture<ChunkResult<ChunkAccess>>) GET_CHUNK_FUTURE_MAIN_THREAD
                                                        .invokeExact(chunkSource, chunkX, chunkZ, ChunkStatus.SPAWN, false);
                                            } catch (Throwable t) {
                                                throw LookupAccess.raise(t);
                                            }
                                        }, server)
                                        .thenAccept(chunk -> {
                                            LevelChunk loaded = switch (chunk.orElseThrow(IllegalStateException::new)) {
                                                case LevelChunk c -> c;
                                                case ImposterProtoChunk c -> c.getWrapped();
                                                case ProtoChunk proto -> new LevelChunk(chunkSource.level, proto, _ -> {
                                                });
                                                default -> throw new UnsupportedOperationException();
                                            };

                                            values[index] = PresharedChunk.createCache(loaded);
                                        });
                            }
                        }

                        return CompletableFuture.allOf(futures).thenApply(_ -> values)
                                .whenCompleteAsync((_, _) -> {
                                    for (int dx = 0; dx < GRID_SIZE; dx++) {
                                        for (int dz = 0; dz < GRID_SIZE; dz++) {
                                            int chunkX = grid.x() * GRID_SIZE + dx, chunkZ = grid.z() * GRID_SIZE + dz;
                                            chunkSource.removeTicketWithRadius(ticketType, new ChunkPos(chunkX, chunkZ), 0);
                                        }
                                    }

                                    try {
                                        boolean _ = (boolean) RUN_DISTANCE_MANAGER_UPDATES.invokeExact(chunkSource);
                                        PROCESS_UNLOAD.invokeExact(chunkSource.chunkMap, (BooleanSupplier) () -> true);
                                    } catch (Throwable t) {
                                        throw LookupAccess.raise(t);
                                    }
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
            for (int dx = 0; dx < GRID_SIZE; dx++) {
                for (int dz = 0; dz < GRID_SIZE; dz++) {
                    PresharedChunk chunk = value.get(dx * GRID_SIZE + dz);

                    HeightMap.STREAM_CODEC.encode(buffer, chunk.heightmaps());
                    SectionInstance.STREAM_CODEC.encode(buffer, chunk.sections());
                    LevelLightSection.STREAM_CODEC.encode(buffer, chunk.lights());
                    BlockEntityInfo.BLOCK_CODEC.encode(buffer, chunk.blockEntities());
                }
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
