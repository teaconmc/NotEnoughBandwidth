package org.teacon.neb.network.chunk.preshare;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.NotNull;
import org.teacon.neb.NotEnoughBandwidth;

@EventBusSubscriber
public record PresharedChunkRequestPacket(ChunkPos pos) implements CustomPacketPayload {
    public static final Type<PresharedChunkRequestPacket> TYPE = new Type<>(NotEnoughBandwidth.id("s2c/preshared_chunk_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PresharedChunkRequestPacket> STREAM_CODEC = StreamCodec.composite(
            ChunkPos.STREAM_CODEC, PresharedChunkRequestPacket::pos,
            PresharedChunkRequestPacket::new
    );

    @Override
    @NotNull
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    @SubscribeEvent
    private static void on(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NotEnoughBandwidth.MOD_CONTAINER.getModInfo().getVersion().toString());
        registrar.playToServer(TYPE, STREAM_CODEC, (packet, context) -> {
            ServerGamePacketListenerImpl listener = (ServerGamePacketListenerImpl) context.listener();
            ServerPlayer player = listener.player;
            ChunkPos pos = packet.pos;

            if (player.getChunkTrackingView().contains(pos)) {
                LevelChunk chunk = player.level().getChunkSource().getChunkNow(pos.x(), pos.z());
                if (chunk != null) {
                    listener.chunkSender.markChunkPendingToSend(chunk);
                }
            }
        });
    }
}
