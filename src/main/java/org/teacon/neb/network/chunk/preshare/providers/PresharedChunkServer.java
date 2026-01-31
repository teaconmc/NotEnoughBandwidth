package org.teacon.neb.network.chunk.preshare.providers;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import org.jetbrains.annotations.NotNull;
import org.teacon.neb.NotEnoughBandwidth;
import org.teacon.neb.network.chunk.preshare.PresharedChunk;
import org.teacon.neb.network.chunk.preshare.PresharedChunkBundle;
import org.teacon.neb.network.chunk.preshare.PresharedChunkGuardPacket;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

@EventBusSubscriber(modid = NotEnoughBandwidth.MODID)
public class PresharedChunkServer {
    public static volatile PresharedChunkBundle lookup = PresharedChunkBundle.EMPTY;

    @SubscribeEvent
    private static void on(ServerStartedEvent event) throws IOException {
        MinecraftServer server = event.getServer();
        load(server);
    }

    public static void load(MinecraftServer server) throws IOException {
        lookup = PresharedChunkBundle.load(getBundlePath(server), server.registryAccess());
    }

    @SubscribeEvent
    private static void on(ServerStoppingEvent event) {
        unload();
    }

    private static @NotNull Path getBundlePath(MinecraftServer server) {
        return server.getWorldPath(new LevelResource("preshared-chunks.neb"));
    }

    @SubscribeEvent
    private static void on(RegisterConfigurationTasksEvent event) {
        event.register(new ConfigurationTask() {
            private static final Type TYPE = new Type(NotEnoughBandwidth.id("preshare_version_guard"));

            @Override
            public void start(@NotNull Consumer<Packet<?>> connection) {
                connection.accept(new PresharedChunkGuardPacket(PresharedChunkServer.lookup.getVersion()).toVanillaClientbound());
            }

            @Override
            public boolean tick() {
                return true;
            }

            @Override
            public @NotNull Type type() {
                return TYPE;
            }
        });
    }

    public static void create(MinecraftServer server) throws IOException {
        List<PresharedChunk> chunks = new ArrayList<>();
        ServerLevel level = Objects.requireNonNull(server.getLevel(Level.OVERWORLD));
        new ChunkTrackingView.Positioned(ChunkPos.ZERO, server.getPlayerList().getViewDistance()).forEach(pos -> {
            LevelChunk chunk = level.getChunk(pos.x(), pos.z());
            PresharedChunk presharedChunk = PresharedChunk.createCache(chunk);
            chunks.add(presharedChunk);
        });

        new PresharedChunkBundle(chunks).write(getBundlePath(server), server.registryAccess());
    }

    public static void unload() {
        lookup = PresharedChunkBundle.EMPTY;
    }
}
