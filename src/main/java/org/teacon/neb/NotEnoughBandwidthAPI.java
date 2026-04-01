package org.teacon.neb;

import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.teacon.neb.network.chunk.preshare.providers.PresharedChunkServer;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface NotEnoughBandwidthAPI {
    static CompletableFuture<UUID> savePresharedChunkBundleTo(Path path) {
        return PresharedChunkServer.create(ServerLifecycleHooks.getCurrentServer(), path);
    }
}
