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
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import org.jspecify.annotations.NullMarked;
import org.teacon.neb.NEBConfigs;
import org.teacon.neb.NotEnoughBandwidth;
import org.teacon.neb.network.chunk.cache.CachedChunkDebugOverlay;
import org.teacon.neb.network.chunk.preshare.PresharedChunk;
import org.teacon.neb.network.chunk.preshare.PresharedChunkPacket;
import org.teacon.neb.network.chunk.preshare.PresharedChunkRequestPacket;
import org.teacon.neb.network.chunk.preshare.grid.PresharedChunkSource;
import org.teacon.neb.network.chunk.preshare.grid.PresharedChunksIO;
import org.teacon.neb.network.chunk.preshare.grid.repos.IPresharedChunkSource;
import org.teacon.neb.network.chunk.preshare.grid.repos.PresharedChunkLocalSource;
import org.teacon.neb.network.chunk.preshare.grid.repos.PresharedChunkRemoteSource;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@EventBusSubscriber(Dist.CLIENT)
@NullMarked
public class PresharedChunkClient {
    private static final AttributeKey<PresharedChunkSource> SOURCE = AttributeKey.newInstance(NotEnoughBandwidth.id("preshared_chunk_source").toString());

    public static void handleLogin(Connection connection, RegistryAccess registryAccess) throws IOException {
        List<IPresharedChunkSource> sources = new ArrayList<>(2);

        String version = NEBConfigs.PRESHARED_CHUNK_STATIC_DISPATCH_VERSION.get();
        if (!version.isEmpty()) {
            Path root = Minecraft.getInstance().gameDirectory.toPath().resolve("preshared-chunks");
            Path path = root.resolve(version).normalize();
            if (!Files.isDirectory(path)) {
                connection.disconnect(Component.translatable("neb.preshared.bundle_missing", version));
                return;
            }
            path = path.toRealPath();
            if (!path.startsWith(root.toRealPath())) {
                connection.disconnect(Component.translatable("neb.preshared.bundle_missing", version));
                return;
            }

            sources.add(new PresharedChunkLocalSource(path));

            String url = NEBConfigs.PRESHARED_CHUNK_DYNAMIC_DISPATCH_URL.get();
            if (!url.isEmpty()) {
                sources.add(new PresharedChunkRemoteSource(version, url, new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        return List.of(Minecraft.getInstance().getProxy());
                    }

                    @Override
                    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                    }
                }));
            }
        }

        if (sources.isEmpty()) {
            return;
        }
        connection.channel().attr(SOURCE).set(new PresharedChunkSource(
                registryAccess,
                PresharedChunksIO.ofExecutorService(Runtime.getRuntime().availableProcessors(), "Client Chunk Decompressor [Native]"),
                sources
        ));
    }

    @SubscribeEvent
    private static void on(ClientPlayerNetworkEvent.LoggingOut event) {
        Connection conn = event.getConnection();
        if (conn != null) {
            PresharedChunkSource source = conn.channel().attr(SOURCE).getAndSet(null);
            if (source != null) {
                source.close();
            }
        }
    }

    @SubscribeEvent
    private static void on(RegisterClientPayloadHandlersEvent event) {
        event.register(PresharedChunkPacket.TYPE, HandlerThread.NETWORK, (packet, context) -> {
            LocalPlayer player = PresharedChunkPacketClientImpl.getLocalPlayer();
            PresharedChunkSource source = context.connection().channel().attr(SOURCE).get();
            if (source == null || (player != null && player.level().dimension() != Level.OVERWORLD)) {
                context.disconnect(Component.literal("Receiving unknown preshared-chunks: " + packet.pos()));
                throw new IllegalStateException("Receiving unknown preshared-chunks.");
            }

            PresharedChunk chunk;
            switch (source.load(packet.pos().pack())) {
                case null -> {
                    context.disconnect(Component.literal("Receiving unknown preshared-chunks: " + packet.pos()));
                    throw new IllegalStateException("Receiving unknown preshared-chunks.");
                }
                case PresharedChunkSource.Loaded(PresharedChunk c) -> chunk = c;
                case PresharedChunkSource.Pending pending -> {
                    pending.thenRunAsync(context.channelHandlerContext().executor(), () -> context.reply(new PresharedChunkRequestPacket(packet.pos())));
                    return;
                }
            }

            if (player != null) {
                handle(packet, chunk, context, player);
            } else {
                context.enqueueWork(() -> {
                    ProfilerFiller profiler = Profiler.get();
                    profiler.push("decodePresharedChunk");
                    handle(packet, chunk, context, Objects.requireNonNull(Minecraft.getInstance().player));
                    profiler.pop();
                });
            }
        });
    }

    private static void handle(PresharedChunkPacket packet, PresharedChunk chunk, IPayloadContext context, LocalPlayer player) {
        if (player.level().dimension() != Level.OVERWORLD) {
            throw new IllegalStateException("Receiving unknown preshared-chunks.");
        }

        ClientboundLevelChunkWithLightPacket pkt = PresharedChunkPacketClientImpl.makeVanillaChunkPacket(packet, chunk);
        context.enqueueWork(() -> {
            context.handle(pkt);
            CachedChunkDebugOverlay.mark(ChunkPos.pack(pkt.getX(), pkt.getZ()), CachedChunkDebugOverlay.STATE_RECEIVE_PRESHARED_CHUNK);
        });
    }
}
