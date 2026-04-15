package org.teacon.neb.network.chunk.preshare;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.NotNull;
import org.teacon.neb.NotEnoughBandwidth;

@EventBusSubscriber
public record PresharedChunkVersionPacket(String version) implements CustomPacketPayload {
    public static final Type<PresharedChunkVersionPacket> TYPE = new Type<>(NotEnoughBandwidth.id("s2c/preshared_chunk_version"));

    public static final StreamCodec<ByteBuf, PresharedChunkVersionPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PresharedChunkVersionPacket::version,
            PresharedChunkVersionPacket::new
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
