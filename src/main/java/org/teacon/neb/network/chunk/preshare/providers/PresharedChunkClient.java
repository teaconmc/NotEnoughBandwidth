package org.teacon.neb.network.chunk.preshare.providers;

import io.netty.util.AttributeKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import org.jspecify.annotations.NullMarked;
import org.teacon.neb.NotEnoughBandwidth;
import org.teacon.neb.network.chunk.cache.CachedChunkDebugOverlay;
import org.teacon.neb.network.chunk.preshare.PresharedChunk;
import org.teacon.neb.network.chunk.preshare.PresharedChunkBundle;
import org.teacon.neb.network.chunk.preshare.PresharedChunkGuardPacket;
import org.teacon.neb.network.chunk.preshare.PresharedChunkPacket;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

@EventBusSubscriber(Dist.CLIENT)
@NullMarked
public class PresharedChunkClient {
    private static final AttributeKey<PresharedChunkBundle> BUNDLE = AttributeKey.newInstance(NotEnoughBandwidth.id("preshared_bundle").toString());
    private static final AttributeKey<UUID> VERSION = AttributeKey.newInstance(NotEnoughBandwidth.id("preshared_bundle_version").toString());

    public static void handleLogin(Connection connection, RegistryAccess registryAccess) throws IOException {
        PresharedChunkBundle bundle;

        if (connection.isMemoryConnection()) {
            bundle = PresharedChunkServer.lookup;
        } else {
            UUID version = connection.channel().attr(VERSION).get();
            if (version == null) {
                return;
            }

            bundle = PresharedChunkBundle.load(
                    Minecraft.getInstance().gameDirectory.toPath().resolve("preshared-chunks/" + version + ".neb"),
                    registryAccess
            );
            if (bundle == null || !bundle.getVersion().equals(version)) {
                connection.disconnect(Component.translatable("neb.preshared.bundle_missing", version.toString()));
                return;
            }
        }

        if (bundle != null) {
            connection.channel().attr(BUNDLE).set(bundle);
        }
    }

    @SubscribeEvent
    private static void on(RegisterClientPayloadHandlersEvent event) {
        event.register(PresharedChunkPacket.TYPE, HandlerThread.NETWORK, (packet, listener) -> {
            LocalPlayer player = PresharedChunkPacketClientImpl.getLocalPlayer();

            if (player != null) {
                handle(packet, listener, player);
            } else {
                listener.enqueueWork(() -> {
                    ProfilerFiller profiler = Profiler.get();
                    profiler.push("decodePresharedChunk");
                    handle(packet, listener, Objects.requireNonNull(Minecraft.getInstance().player));
                    profiler.pop();
                });
            }
        });

        event.register(PresharedChunkGuardPacket.TYPE, HandlerThread.NETWORK, (packet, context) -> {
            context.connection().channel().attr(VERSION).set(packet.version());
        });
    }

    private static void handle(PresharedChunkPacket packet, IPayloadContext context, LocalPlayer player) {
        PresharedChunk preshared = PresharedChunkBundle.getChunk(
                context.connection().channel().attr(BUNDLE).get(),
                player.level(), packet.pos()
        );
        if (preshared == null) {
            throw new IllegalStateException("Receiving unknown preshared-chunks.");
        }

        ClientboundLevelChunkWithLightPacket pkt = PresharedChunkPacketClientImpl.buildVanillaChunkPacket(packet, preshared);
        context.enqueueWork(() -> {
            context.handle(pkt);
            CachedChunkDebugOverlay.mark(ChunkPos.pack(pkt.getX(), pkt.getZ()), CachedChunkDebugOverlay.STATE_RECEIVE_PRESHARED_CHUNK);
        });
    }
}
