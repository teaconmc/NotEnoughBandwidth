package org.teacon.neb;

import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.teacon.neb.network.chunk.preshare.grid.PresharedChunksIO;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public interface NotEnoughBandwidthAPI {
    static CompletableFuture<?> savePresharedChunks(Path directory, LongIterator iterator) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(iterator, "iterator");

        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Must be a directory.");
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalArgumentException("No Minecraft server is running!");
        }

        CompletableFuture<?> future = new CompletableFuture<>();
        server.execute(() -> PresharedChunksIO.save(directory, iterator).thenRun(() -> future.complete(null)));
        return future;
    }
}
