package org.teacon.neb.network.chunk.preshare;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.NotNull;
import org.teacon.neb.NotEnoughBandwidth;

import java.util.UUID;

@EventBusSubscriber
public record PresharedChunkGuardPacket(
        UUID version
) implements CustomPacketPayload {
    public static final Type<PresharedChunkGuardPacket> TYPE = new Type<>(NotEnoughBandwidth.id("s2c/preshared_chunk_guard"));

    public static final StreamCodec<FriendlyByteBuf, PresharedChunkGuardPacket> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, PresharedChunkGuardPacket::version,
            PresharedChunkGuardPacket::new
    );

    @Override
    @NotNull
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    @SubscribeEvent
    private static void on(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NotEnoughBandwidth.MOD_CONTAINER.getModInfo().getVersion().toString());
        registrar.configurationToClient(TYPE, STREAM_CODEC);
    }
}
