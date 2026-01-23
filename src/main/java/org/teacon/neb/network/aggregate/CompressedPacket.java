package org.teacon.neb.network.aggregate;

import com.mojang.logging.annotations.MethodsReturnNonnullByDefault;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import org.jetbrains.annotations.NotNull;
import org.teacon.neb.NotEnoughBandwidth;

import javax.annotation.ParametersAreNonnullByDefault;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public record CompressedPacket(
        PacketType<@NotNull CompressedPacket> type,
        ByteBuf buf
) implements Packet<@NotNull PacketListener> {
    public static final PacketType<@NotNull CompressedPacket> S_TYPE = new PacketType<>(PacketFlow.SERVERBOUND, NotEnoughBandwidth.id("c2s/compressed"));
    public static final PacketType<@NotNull CompressedPacket> C_TYPE = new PacketType<>(PacketFlow.CLIENTBOUND, NotEnoughBandwidth.id("s2c/compressed"));

    public static final StreamCodec<@NotNull FriendlyByteBuf, @NotNull CompressedPacket> S_CODEC = ofCodec(S_TYPE), C_CODEC = ofCodec(C_TYPE);

    private static StreamCodec<@NotNull FriendlyByteBuf, @NotNull CompressedPacket> ofCodec(PacketType<@NotNull CompressedPacket> type) {
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
