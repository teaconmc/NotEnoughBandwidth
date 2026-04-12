package org.teacon.neb.network.chunk.preshare.providers;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.jetbrains.annotations.Nullable;
import org.teacon.neb.network.chunk.preshare.grid.PresharedChunkSource;
import org.teacon.neb.network.chunk.preshare.grid.PresharedChunksIO;
import org.teacon.neb.network.chunk.preshare.grid.repos.PresharedChunkLocalSource;

import java.nio.file.Files;
import java.nio.file.Path;

@EventBusSubscriber
public class PresharedChunkServer {
    @Nullable
    private static PresharedChunkSource source = null;

    @SubscribeEvent
    private static void on(ServerStartedEvent event) {
        load(event.getServer());
    }

    public static void load(MinecraftServer server) {
        Path directory = server.getWorldPath(new LevelResource("preshared-chunks"));
        if (!Files.isDirectory(directory)) {
            return;
        }

        source = new PresharedChunkSource(
                server.registryAccess(),
                PresharedChunksIO.ofExecutorService(Runtime.getRuntime().availableProcessors(), "Server Chunk Decompressor [Native]"),
                new PresharedChunkLocalSource(directory)
        );
    }

    @SubscribeEvent
    private static void on(ServerStoppingEvent event) {
        if (source != null) {
            source.close();
            source = null;
        }
    }

    @Nullable
    public static PresharedChunkSource.IResult makePacket(LevelChunk chunk) {
        if (chunk.getLevel().dimension() != Level.OVERWORLD || source == null) {
            return null;
        }

        return source.load(chunk.getPos().pack());
    }
}
