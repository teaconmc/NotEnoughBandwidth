package org.teacon.neb.network.chunk.preshare.grid;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.teacon.neb.network.aggregate.compress.CompressContext;
import org.teacon.neb.network.chunk.preshare.PresharedChunk;
import org.teacon.neb.network.chunk.preshare.data.BlockEntityInfo;
import org.teacon.neb.network.chunk.preshare.data.HeightMap;
import org.teacon.neb.network.chunk.preshare.data.LevelLightSection;
import org.teacon.neb.network.chunk.preshare.data.SectionInstance;
import org.teacon.neb.network.chunk.preshare.grid.repos.PresharedChunkLocalSource;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.teacon.neb.utils.GridPos.GRID_SIZE;

public final class PresharedChunksIO {
    private static final Logger LOGGER = LoggerFactory.getLogger(PresharedChunksIO.class);

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
        ServerLevel overworld = server.overworld();
        LongSet scheduled = new LongOpenHashSet();
        ThreadPoolExecutor executor = ofExecutorService("Server Chunk Compressor [Native]");
        AtomicInteger pending = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                Files.writeString(PresharedChunkLocalSource.resolveIndex(directory), UUID.randomUUID().toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.error("Cannot write index file.", e);
            }
        });

        pollTasks(directory, chunks, pending, scheduled, overworld, executor, server, future);
        return future;
    }

    public static ThreadPoolExecutor ofExecutorService(String name) {
        int count = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
                0, count,
                30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                Thread.ofPlatform().name("NEB " + name).daemon().factory()
        );
    }

    private static void pollTasks(
            Path directory,
            LongIterator chunks,
            AtomicInteger pending,
            LongSet scheduled,
            ServerLevel overworld,
            ThreadPoolExecutor executor,
            MinecraftServer server,
            CompletableFuture<Void> future
    ) {
        while (pending.get() < executor.getMaximumPoolSize() * 4 && chunks.hasNext()) {
            GridPos grid = GridPos.fromChunk(ChunkPos.unpack(chunks.nextLong()));
            long gridXZ = grid.pack();
            if (scheduled.contains(gridXZ)) {
                continue;
            }
            scheduled.add(gridXZ);

            List<PresharedChunk> value = new ArrayList<>(GRID_SIZE * GRID_SIZE);
            for (int dx = 0; dx < GRID_SIZE; dx++) {
                for (int dz = 0; dz < GRID_SIZE; dz++) {
                    value.add(PresharedChunk.createCache(overworld.getChunk(grid.x() * GRID_SIZE + dx, grid.z() * GRID_SIZE + dz)));
                }
            }

            pending.getAndIncrement();
            executor.submit(() -> {
                try {
                    write(directory.resolve(PresharedChunkLocalSource.getName(gridXZ)), gridXZ, value, server.registryAccess());
                } catch (Throwable t) {
                    LOGGER.error("Cannot create preshared chunks for grid: {}", GridPos.unpack(gridXZ), t);
                }

                if (pending.decrementAndGet() < executor.getMaximumPoolSize() * 4) {
                    server.execute(() -> pollTasks(directory, chunks, pending, scheduled, overworld, executor, server, future));
                }
            });
        }

        if (!chunks.hasNext()) {
            executor.shutdown();
            Thread.startVirtualThread(() -> {
                try {
                    if (!executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                        throw new AssertionError();
                    }
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }

                future.complete(null);
            });
        }
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
