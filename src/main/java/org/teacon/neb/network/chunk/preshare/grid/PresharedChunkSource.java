package org.teacon.neb.network.chunk.preshare.grid;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.teacon.neb.NEBConfigs;
import org.teacon.neb.network.chunk.preshare.PresharedChunk;
import org.teacon.neb.network.chunk.preshare.grid.repos.IPresharedChunkSource;
import org.teacon.neb.utils.ContextByteBuf;
import org.teacon.neb.utils.GridPos;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.WeakReference;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class PresharedChunkSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(PresharedChunkSource.class);

    private final ExecutorService decompressor;
    private final List<IPresharedChunkSource> sources;
    private final RegistryAccess registryAccess;
    private final Executor managedThreadIdeExecutor;
    private final Thread managedThread = Thread.currentThread();

    private final Arena ARENA = Arena.ofShared();

    /// chunk_pos -> chunk
    private final Long2ObjectLinkedOpenHashMap<PresharedChunk> cacheL1 = new Long2ObjectLinkedOpenHashMap<>();
    /// grid_pos -> buffer
    private final Long2ObjectMap<MemorySegment> cacheL2 = new Long2ObjectOpenHashMap<>();

    private record Request(long timestamp, CompletableFuture<@Nullable RequestResponse> future) {
    }

    private record RequestResponse(MemorySegment segment, List<PresharedChunk> chunks) {
    }

    /// grid_pos -> async request
    private final Long2ObjectLinkedOpenHashMap<Request> futures = new Long2ObjectLinkedOpenHashMap<>();

    public PresharedChunkSource(Executor managedThreadIdeExecutor, RegistryAccess registryAccess, ExecutorService decompressor, List<IPresharedChunkSource> sources) {
        this.managedThreadIdeExecutor = managedThreadIdeExecutor;
        this.decompressor = decompressor;
        this.sources = sources;
        this.registryAccess = registryAccess;
    }

    public sealed interface IResult {
    }

    public record Empty() implements IResult {
        public static final Empty INSTANCE = new Empty();
    }

    public record Loaded(PresharedChunk chunk) implements IResult {
    }

    public record Failed() implements IResult {
        public static final Failed INSTANCE = new Failed();
    }

    public static final class Pending implements IResult {
        private final CompletableFuture<?> future;
        private final Thread desiredThread;

        private Pending(CompletableFuture<?> future, Thread desiredThread) {
            this.future = future;
            this.desiredThread = desiredThread;
        }

        public void thenRunAsync(Executor executor, BooleanConsumer runnable) {
            future.whenCompleteAsync((_, t) -> {
                if (Thread.currentThread() != desiredThread) {
                    throw new IllegalArgumentException("Executor doesn't deferred runnable to desired thread: " + desiredThread);
                }
                runnable.accept(t == null);
            }, executor);
        }
    }

    public IResult load(long pos, boolean shouldSchedule) {
        if (!FMLEnvironment.isProduction() && Thread.currentThread() != managedThread) {
            throw new IllegalMonitorStateException(Objects.toIdentityString(this) + " is managed by " + managedThread);
        }

        PresharedChunk chunk = cacheL1.getAndMoveToLast(pos);
        if (chunk != null) {
            return new Loaded(chunk);
        }

        long gridXZ = GridPos.fromChunk(ChunkPos.unpack(pos)).pack();
        MemorySegment segment = cacheL2.get(gridXZ);
        if (segment == MemorySegment.NULL) {
            return Empty.INSTANCE;
        }

        @Nullable Request request = futures.get(gridXZ);
        switch (request == null ? null : request.future.state()) {
            case null -> {
            }
            case SUCCESS -> {
                futures.remove(gridXZ);

                RequestResponse result = request.future.resultNow();
                liftL2(gridXZ, result);

                chunk = cacheL1.get(pos);
                return chunk == null ? Empty.INSTANCE : new Loaded(chunk);
            }
            case RUNNING -> {
                return new Pending(request.future, managedThread);
            }
            case FAILED, CANCELLED -> {
                if (request.timestamp >= System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(NEBConfigs.PRESHARED_CHUNK_RETRY_TIMEOUT.get())) {
                    return Failed.INSTANCE;
                }
            }
        }

        if (!shouldSchedule) {
            return Empty.INSTANCE;
        }
        request = prepareL3(segment, gridXZ);
        futures.put(gridXZ, request);
        return new Pending(request.future, managedThread);
    }

    private void liftL2(long gridXZ, @Nullable RequestResponse result) {
        if (result == null) {
            cacheL2.putIfAbsent(gridXZ, MemorySegment.NULL);
            return;
        }
        cacheL2.putIfAbsent(gridXZ, result.segment);

        int expected = NEBConfigs.PRESHARED_CHUNK_CACHE_L1_MAX.get() - result.chunks.size();
        if (expected <= 0) {
            cacheL1.clear();
        } else {
            for (int delta = cacheL1.size() - expected, i = 0; i < delta; i++) {
                cacheL1.removeFirst();
            }
        }

        for (PresharedChunk chunk : result.chunks) {
            cacheL1.put(chunk.pos().pack(), chunk);
        }
    }

    private Request prepareL3(@Nullable MemorySegment loadedSegment, long gridXZ) {
        CompletableFuture<@Nullable MemorySegment> loadFuture;
        if (loadedSegment != null) {
            loadFuture = CompletableFuture.completedFuture(loadedSegment);
        } else {
            loadFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    for (IPresharedChunkSource source : sources) {
                        Path path = source.tryLoad(gridXZ);
                        if (path != null) {
                            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                                return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), ARENA);
                            }
                        }
                    }
                } catch (Exception e) {
                    if (e instanceof IOException && e.getClass().getName().startsWith("java.net.")) {
                        LOGGER.warn("Cannot load preshared chunks: {}\n{}", GridPos.unpack(gridXZ), e.toString());
                    } else {
                        LOGGER.warn("Cannot load preshared chunks: {}", GridPos.unpack(gridXZ), e);
                    }
                    throw e instanceof RuntimeException re ? re : new RuntimeException(e);
                }
                return null;
            }, Executors.newVirtualThreadPerTaskExecutor());
        }

        CompletableFuture<@Nullable RequestResponse> parseFuture = loadFuture.thenApplyAsync(segment -> {
            if (segment == null) {
                return null;
            }

            List<PresharedChunk> chunks = PresharedChunksIO.read(new ContextByteBuf(
                    Unpooled.wrappedBuffer(segment.asByteBuffer()),
                    registryAccess, ConnectionType.NEOFORGE
            ));
            return new RequestResponse(segment, chunks);
        }, decompressor);

        Request request = new Request(System.currentTimeMillis(), parseFuture);

        WeakReference<Request> reference = new WeakReference<>(request);
        parseFuture.thenAcceptAsync(response -> {
            Request current = reference.get();
            if (current != null && futures.remove(gridXZ, current)) {
                liftL2(gridXZ, response);
            }
        }, managedThreadIdeExecutor);

        return request;
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
