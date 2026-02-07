package org.teacon.neb.network.aggregate;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import org.teacon.neb.NotEnoughBandwidth;

public record CompressedPacket(
        PacketType<CompressedPacket> type,
        ByteBuf buf
) implements Packet<PacketListener> {
    public static final PacketType<CompressedPacket> S_TYPE = new PacketType<>(PacketFlow.SERVERBOUND, NotEnoughBandwidth.id("c2s/compressed"));
    public static final PacketType<CompressedPacket> C_TYPE = new PacketType<>(PacketFlow.CLIENTBOUND, NotEnoughBandwidth.id("s2c/compressed"));

    public static final StreamCodec<FriendlyByteBuf, CompressedPacket> S_CODEC = ofCodec(S_TYPE), C_CODEC = ofCodec(C_TYPE);

    private static StreamCodec<FriendlyByteBuf, CompressedPacket> ofCodec(PacketType<CompressedPacket> type) {
        return new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf target, CompressedPacket packet) {
                ByteBuf buf = packet.buf().retainedDuplicate();
                try {
                    target.writeBytes(buf);
                } finally {
                    buf.release();
                }
            }

            @Override
            public CompressedPacket decode(FriendlyByteBuf compressed) {
                return new CompressedPacket(type, compressed.readBytes(compressed.readableBytes()));
            }
        };
    }

    @Override
    public void handle(PacketListener listener) {
        throw new AssertionError("CompressedPacket should be handled by CompressedDecoder.");
    }
}
