package org.teacon.neb.network.chunk.preshare.grid;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.teacon.neb.network.chunk.preshare.PresharedChunk;
import org.teacon.neb.network.chunk.preshare.grid.repos.IPresharedChunkSource;
import org.teacon.neb.utils.ContextByteBuf;
import org.teacon.neb.utils.GridPos;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

public final class PresharedChunkSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(PresharedChunkSource.class);
    private static final int CACHE_L1_MAX = 2048;

    private final ExecutorService decompressor;
    private final List<IPresharedChunkSource> sources;
    private final RegistryAccess registryAccess;

    private final Arena ARENA = Arena.ofShared();

    // chunk_pos -> chunk
    private final Long2ObjectLinkedOpenHashMap<PresharedChunk> cacheL1 = new Long2ObjectLinkedOpenHashMap<>();
    // grid_pos -> buffer
    private final Long2ObjectMap<MemorySegment> cacheL2 = new Long2ObjectOpenHashMap<>();

    private record DecompressResult(MemorySegment segment, @Nullable List<PresharedChunk> chunks) {
    }

    // grid_pos  -> async (buffer + chunks)
    private final Long2ObjectLinkedOpenHashMap<CompletableFuture<@Nullable DecompressResult>> cacheL3 = new Long2ObjectLinkedOpenHashMap<>();

    public PresharedChunkSource(RegistryAccess registryAccess, ExecutorService decompressor, IPresharedChunkSource... sources) {
        this(registryAccess, decompressor, Arrays.asList(sources));
    }

    public PresharedChunkSource(RegistryAccess registryAccess, ExecutorService decompressor, List<IPresharedChunkSource> sources) {
        this.decompressor = decompressor;
        this.sources = sources;
        this.registryAccess = registryAccess;
    }

    public sealed interface IResult {
    }

    public record Loaded(PresharedChunk chunk) implements IResult {
    }

    public static final class Pending implements IResult {
        private final CompletableFuture<?> future;
        private final Thread desiredThread;

        private Pending(CompletableFuture<?> future) {
            this.future = future;
            this.desiredThread = Thread.currentThread();
        }

        public void thenRunAsync(Executor executor, Runnable runnable) {
            future.thenRunAsync(() -> {
                if (Thread.currentThread() != desiredThread) {
                    throw new IllegalArgumentException("Executor doesn't deferred runnable to desired thread: " + desiredThread);
                }
                runnable.run();
            }, executor);
        }
    }

    @Nullable
    public IResult load(long pos) {
        PresharedChunk chunk = cacheL1.getAndMoveToLast(pos);
        if (chunk != null) {
            return new Loaded(chunk);
        }

        long gridXZ = GridPos.fromChunk(ChunkPos.unpack(pos)).pack();
        MemorySegment segment = cacheL2.get(gridXZ);
        if (segment == MemorySegment.NULL) {
            return null;
        }

        CompletableFuture<DecompressResult> future = cacheL3.get(gridXZ);
        switch (future == null ? null : future.state()) {
            case SUCCESS -> {
                cacheL3.remove(gridXZ);

                DecompressResult result = future.resultNow();
                MemorySegment previous = cacheL2.putIfAbsent(gridXZ, result.segment);
                if (previous != null && previous != result.segment) {
                    throw new AssertionError();
                }

                if (result.chunks == null) {
                    return null;
                }
                return new Loaded(liftL2(pos, result.chunks));
            }
            case RUNNING -> {
                return new Pending(future);
            }
            case null, default -> {
                future = prepareL3(segment, gridXZ);
                cacheL3.put(gridXZ, future);
                return new Pending(future);
            }
        }
    }

    private PresharedChunk liftL2(long pos, List<PresharedChunk> chunks) {
        int expected = CACHE_L1_MAX - chunks.size();
        if (expected <= 0) {
            cacheL1.clear();
        } else {
            for (int delta = cacheL1.size() - expected, i = 0; i < delta; i++) {
                cacheL1.removeFirst();
            }
        }

        for (PresharedChunk chunk : chunks) {
            cacheL1.put(chunk.pos().pack(), chunk);
        }

        for (PresharedChunk chunk : chunks) {
            if (chunk.pos().pack() == pos) {
                return chunk;
            }
        }
        throw new IllegalStateException("Cannot load chunk from cacheL2: must contain chunk " + ChunkPos.unpack(pos));
    }

    private CompletableFuture<DecompressResult> prepareL3(@Nullable MemorySegment loadedSegment, long gridXZ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                MemorySegment segment = loadedSegment;
                if (segment == null) {
                    for (IPresharedChunkSource source : sources) {
                        Path path = source.tryLoad(gridXZ);
                        if (path != null) {
                            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                                segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), ARENA);
                                break;
                            }
                        }
                    }
                }
                if (segment == null) {
                    return new DecompressResult(MemorySegment.NULL, null);
                }

                List<PresharedChunk> chunks = PresharedChunksIO.read(new ContextByteBuf(
                        Unpooled.wrappedBuffer(segment.asByteBuffer()),
                        registryAccess, ConnectionType.NEOFORGE
                ));
                return new DecompressResult(segment, chunks);
            } catch (Exception e) {
                LOGGER.warn("Cannot load preshared chunks: {}", GridPos.unpack(gridXZ), e);
                throw new RuntimeException(e);
            }
        }, decompressor);
    }

    public void close() {
        ARENA.close();
        decompressor.shutdown();
        for (IPresharedChunkSource source : sources) {
            try {
                source.close();
            } catch (IOException _) {
            }
        }
    }
}
