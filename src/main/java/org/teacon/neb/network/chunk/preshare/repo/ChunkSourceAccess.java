package org.teacon.neb.network.chunk.preshare.repo;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.teacon.neb.utils.vm.LookupAccess;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/* package-private */ class ChunkSourceAccess {
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

    static void processUnloads(ServerChunkCache chunkSource) {
        try {
            PROCESS_UNLOAD.invokeExact(chunkSource.chunkMap, (BooleanSupplier) () -> true);
        } catch (Throwable t) {
            throw LookupAccess.raise(t);
        }
    }

    static void runDistanceManagerUpdates(ServerChunkCache chunkSource) {
        try {
            boolean _ = (boolean) RUN_DISTANCE_MANAGER_UPDATES.invokeExact(chunkSource);
        } catch (Throwable t) {
            throw LookupAccess.raise(t);
        }
    }

    @SuppressWarnings("unchecked")
    static CompletableFuture<ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>> getChunkFutureMainThread(ServerChunkCache chunkSource, int chunkX, int chunkZ, ChunkStatus status, boolean loadOrGenerate) {
        try {
            return (CompletableFuture<ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>>) GET_CHUNK_FUTURE_MAIN_THREAD
                    .invokeExact(chunkSource, chunkX, chunkZ, status, loadOrGenerate);
        } catch (Throwable t) {
            throw LookupAccess.raise(t);
        }
    }
}
