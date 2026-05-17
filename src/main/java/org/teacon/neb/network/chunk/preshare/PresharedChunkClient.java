package org.teacon.neb.network.chunk.preshare;

import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
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
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.teacon.neb.NEBConfigs;
import org.teacon.neb.NotEnoughBandwidth;
import org.teacon.neb.network.chunk.debug.ChunkReceivingEvent;
import org.teacon.neb.network.chunk.preshare.data.PresharedChunk;
import org.teacon.neb.network.chunk.preshare.packets.PresharedChunkPacket;
import org.teacon.neb.network.chunk.preshare.packets.PresharedChunkRequestPacket;
import org.teacon.neb.network.chunk.preshare.packets.PresharedChunkVersionPacket;
import org.teacon.neb.network.chunk.preshare.repo.PresharedChunkSource;
import org.teacon.neb.network.chunk.preshare.repo.PresharedChunksIO;
import org.teacon.neb.network.chunk.preshare.repo.impl.IPresharedChunkSource;
import org.teacon.neb.network.chunk.preshare.repo.impl.PresharedChunkLocalSource;
import org.teacon.neb.network.chunk.preshare.repo.impl.PresharedChunkRemoteSource;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@EventBusSubscriber(Dist.CLIENT)
@NullMarked
public class PresharedChunkClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(PresharedChunkClient.class);

    private static final AttributeKey<String> SOURCE_VERSION = AttributeKey.newInstance(NotEnoughBandwidth.id("preshared_chunk_source_version").toString());
    private static final AttributeKey<PresharedChunkSource> SOURCE = AttributeKey.newInstance(NotEnoughBandwidth.id("preshared_chunk_source").toString());

    public static void handleLogin(Connection connection, RegistryAccess registryAccess) throws IOException {
        Attribute<PresharedChunkSource> sourceAttribute = connection.channel().attr(SOURCE);
        if (sourceAttribute.get() != null) {
            return;
        }

        List<IPresharedChunkSource> sources = new ArrayList<>(2);

        String version = connection.channel().attr(SOURCE_VERSION).get();
        if (version != null && !version.isEmpty()) {
            Path path = locatePresharedDirectory(connection, version);
            if (path == null) {
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
        sourceAttribute.set(new PresharedChunkSource(
                connection.channel().eventLoop(),
                registryAccess,
                PresharedChunksIO.ofExecutorService("Client Chunk Decompressor [Native]"),
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

    @Nullable
    private static Path locatePresharedDirectory(Connection connection, String version) throws IOException {
        Path root = Minecraft.getInstance().gameDirectory.toPath().resolve("preshared-chunks");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path edition : stream) {
                if (Files.isDirectory(edition)) {
                    Path index = PresharedChunkLocalSource.resolveIndex(edition);
                    if (Files.isReadable(index) && version.equals(Files.readString(index, StandardCharsets.UTF_8))) {
                        Path path = edition.normalize().toRealPath();
                        if (path.startsWith(root.toRealPath())) {
                            return path;
                        }
                    }
                }
            }
        }

        connection.disconnect(Component.translatable("neb.preshared.bundle_missing", version));
        return null;
    }

    @SubscribeEvent
    private static void on(RegisterClientPayloadHandlersEvent event) {
        event.register(PresharedChunkVersionPacket.TYPE, (packet, context) -> {
            context.connection().channel().attr(SOURCE_VERSION).set(packet.version());
        });

        event.register(PresharedChunkPacket.TYPE, HandlerThread.NETWORK, (packet, context) -> {
            LocalPlayer player = PresharedChunkPacketClientImpl.getLocalPlayer();
            PresharedChunkSource source = context.connection().channel().attr(SOURCE).get();
            if (source == null || (player != null && player.level().dimension() != Level.OVERWORLD)) {
                context.disconnect(Component.literal("Receiving unknown preshared-chunks: " + packet.pos()));
                return;
            }

            PresharedChunk chunk;
            long pos = packet.pos().pack();
            switch (source.load(pos, true)) { // TODO: Predict player's position and load chunk base from CDN in advance
                case PresharedChunkSource.Empty _, PresharedChunkSource.Failed _ -> {
                    context.enqueueWork(() -> ChunkReceivingEvent.VANILLA_REQUEST.submit(pos));
                    context.reply(new PresharedChunkRequestPacket(packet.pos(), true));
                    return;
                }
                case PresharedChunkSource.Loaded(PresharedChunk c) -> chunk = c;
                case PresharedChunkSource.Pending pending -> { // TODO: Request vanilla ClientboundLevelChunkWithLight immediately if the chunk is too close to the player.
                    pending.thenRunAsync(
                            success -> {
                                context.reply(new PresharedChunkRequestPacket(packet.pos(), !success));
                                if (success) {
                                    context.enqueueWork(() -> ChunkReceivingEvent.PRESHARED_REQUEST.submit(pos));
                                } else {
                                    context.enqueueWork(() -> ChunkReceivingEvent.VANILLA_REQUEST.submit(pos));
                                }
                            }
                    );
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
            context.disconnect(Component.literal("Receiving unknown preshared-chunks: " + packet.pos()));
            return;
        }

        ClientboundLevelChunkWithLightPacket pkt = PresharedChunkPacketClientImpl.makeVanillaChunkPacket(packet, chunk);
        context.enqueueWork(() -> {
            context.handle(pkt);
            ChunkReceivingEvent.PRESHARED_RECEIVED.submit(ChunkPos.pack(pkt.getX(), pkt.getZ()));
        });
    }

    public static CompletableFuture<Snapshot> takeSnapshot(int chunkX1, int chunkX2, int chunkZ1, int chunkZ2, @Nullable Snapshot previous) {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (listener != null) {
            Channel channel = listener.getConnection().channel();
            Snapshot snapshot = new Snapshot(chunkX1, chunkX2, chunkZ1, chunkZ2, previous);
            return CompletableFuture.supplyAsync(() -> {
                PresharedChunkSource source = channel.attr(SOURCE).get();
                if (source == null) {
                    throw new IllegalStateException("Preshared Chunks are NOT loaded.");
                }

                for (int x = chunkX1; x <= chunkX2; x++) {
                    for (int z = chunkZ1; z <= chunkZ2; z++) {
                        try {
                            snapshot.put(x, z, switch (source.load(ChunkPos.pack(x, z), false)) {
                                case PresharedChunkSource.Empty _ -> Snapshot.Type.EMPTY;
                                case PresharedChunkSource.Pending _ -> Snapshot.Type.LOADING;
                                case PresharedChunkSource.Failed _ -> Snapshot.Type.FAILED;
                                case PresharedChunkSource.Loaded _ -> Snapshot.Type.LOADED;
                            });
                        } catch (RuntimeException e) {
                            if (LOGGER.isWarnEnabled()) {
                                LOGGER.warn("Cannot take snapshot of chunk [{}, {}].", chunkX1, chunkX2, e);
                            }
                        }
                    }
                }

                return snapshot;
            }, channel.eventLoop());
        }
        return CompletableFuture.failedFuture(new IllegalStateException("Preshared Chunks are NOT loaded."));
    }

    public static final class Snapshot {
        private final int chunkX1, chunkX2, chunkZ1, chunkZ2;
        private final byte[] buffer;

        public enum Type {
            UNKNOWN, EMPTY, LOADING, LOADED, FAILED;

            private static final Type[] VALUES = values();
        }

        public Snapshot(int chunkX1, int chunkX2, int chunkZ1, int chunkZ2, @Nullable Snapshot previous) {
            this.chunkX1 = chunkX1;
            this.chunkX2 = chunkX2;
            this.chunkZ1 = chunkZ1;
            this.chunkZ2 = chunkZ2;

            int size = (chunkX2 - chunkX1 + 1) * (chunkZ2 - chunkZ1 + 1);
            if (previous != null && previous.buffer.length >= size && previous.buffer.length <= size * 4) {
                this.buffer = previous.buffer;
                Arrays.fill(this.buffer, 0, size, (byte) Type.UNKNOWN.ordinal());
            } else {
                this.buffer = new byte[size];
            }
        }

        private int computeIndex(int chunkX, int chunkZ) {
            if (chunkX < chunkX1 || chunkX > chunkX2 || chunkZ < chunkZ1 || chunkZ > chunkZ2) {
                return -1;
            }

            return (chunkX - chunkX1) * (chunkX2 - chunkX1 + 1) + (chunkZ - chunkZ1);
        }

        private void put(int chunkX, int chunkZ, Type type) {
            int index = computeIndex(chunkX, chunkZ);
            if (index == -1) {
                throw new IndexOutOfBoundsException(String.format("Chunk (%d, %d) is out of bound [(%d, %d), (%d, %d)]", chunkX, chunkZ, chunkX1, chunkZ1, chunkX2, chunkZ2));
            }
            buffer[index] = (byte) type.ordinal();
        }

        public Type get(int chunkX, int chunkZ) {
            int index = computeIndex(chunkX, chunkZ);
            if (index == -1) {
                return Type.UNKNOWN;
            }
            return Type.VALUES[buffer[index]];
        }
    }
}
