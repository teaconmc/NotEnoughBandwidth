package org.teacon.neb.network.chunk.preshare.providers;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.teacon.neb.NEBConfigs;
import org.teacon.neb.NotEnoughBandwidth;
import org.teacon.neb.network.chunk.preshare.PresharedChunk;
import org.teacon.neb.network.chunk.preshare.PresharedChunkBundle;
import org.teacon.neb.network.chunk.preshare.PresharedChunkGuardPacket;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@EventBusSubscriber
public class PresharedChunkServer {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Nullable
    public static volatile PresharedChunkBundle lookup = null;

    @NonNull
    public static Path locatePresharedChunkBundle(MinecraftServer server) {
        return server.getWorldPath(new LevelResource("preshared-chunks.neb"));
    }

    @SubscribeEvent
    private static void on(ServerStartedEvent event) throws IOException {
        MinecraftServer server = event.getServer();
        load(server);
    }

    public static void load(MinecraftServer server) throws IOException {
        lookup = PresharedChunkBundle.load(locatePresharedChunkBundle(server), server.registryAccess());
    }

    @SubscribeEvent
    private static void on(ServerStoppingEvent event) {
        unload();
    }

    public static CompletableFuture<UUID> create(MinecraftServer server, Path path) {
        try {
            Component message = Component.translatable("neb.preshared.create.working");
            server.sendSystemMessage(message);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.connection.getConnection().send(
                        new ClientboundSystemChatPacket(message, true),
                        _ -> {}, true
                );
            }

            List<PresharedChunk> chunks = new ArrayList<>();
            ServerLevel level = server.overworld();

            new ChunkTrackingView.Positioned(
                    ChunkPos.containing(level.getRespawnData().pos()),
                    server.getPlayerList().getViewDistance() + NEBConfigs.PRESHARED_CHUNK_DISTANCE.get()
            ).forEach(pos -> {
                LevelChunk chunk = level.getChunk(pos.x(), pos.z());
                PresharedChunk presharedChunk = PresharedChunk.createCache(chunk);
                chunks.add(presharedChunk);
            });

            PresharedChunkBundle bundle = new PresharedChunkBundle(chunks);
            return CompletableFuture.supplyAsync(() -> {
                try {
                    bundle.write(path, server.registryAccess());
                    return bundle.getVersion();
                } catch (IOException e) {
                    LOGGER.warn("Cannot write Preshared Chunk Bundle to {}", path, e);
                    throw new UncheckedIOException(e);
                }
            });
        } catch (Throwable t) {
            LOGGER.warn("Cannot create Preshared Chunk Bundle.", t);
            return CompletableFuture.<UUID>failedStage(t).toCompletableFuture();
        }
    }

    public static void unload() {
        lookup = null;
    }

    @SubscribeEvent
    private static void on(RegisterConfigurationTasksEvent event) {
        PresharedChunkBundle lookup = PresharedChunkServer.lookup;
        if (lookup == null || event.getListener().getConnection().isMemoryConnection()) {
            return;
        }

        event.register(new ICustomConfigurationTask() {
            private static final Type TYPE = new Type(NotEnoughBandwidth.id("preshare_version_guard"));

            @Override
            public void run(@NotNull Consumer<CustomPacketPayload> connection) {
                connection.accept(new PresharedChunkGuardPacket(lookup.getVersion()));
                event.getListener().finishCurrentTask(TYPE);
            }

            @Override
            @NotNull
            public Type type() {
                return TYPE;
            }
        });
    }
}
