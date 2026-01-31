package org.teacon.neb.network.indexed;

import com.mojang.logging.annotations.MethodsReturnNonnullByDefault;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.teacon.neb.NotEnoughBandwidth;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * Instead of vanilla {@link CustomPacketPayload},
 * we here use such protocol to avoid putting a huge Identifier into ByteBuf.
 *
 * @author USS_Shenzhou, Burning_TNT
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public record IndexPacket(PacketType<@NotNull IndexPacket> type,
                          CustomPacketPayload payload) implements Packet<@NotNull PacketListener> {
    public static final PacketType<@NotNull IndexPacket> S_TYPE = new PacketType<>(PacketFlow.SERVERBOUND, NotEnoughBandwidth.id("c2s/indexed"));
    public static final PacketType<@NotNull IndexPacket> C_TYPE = new PacketType<>(PacketFlow.CLIENTBOUND, NotEnoughBandwidth.id("s2c/indexed"));

    public static final StreamCodec<@NotNull FriendlyByteBuf, @NotNull IndexPacket> S_CODEC = ofCodec(S_TYPE), C_CODEC = ofCodec(C_TYPE);

    public static StreamCodec<@NotNull FriendlyByteBuf, @NotNull IndexPacket> ofCodec(PacketType<@NotNull IndexPacket> packetType) {
        return new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, IndexPacket packet) {
                Identifier type = packet.payload().type().id();

                int index = IndexLookup.getInstance().getIndex(type);
                if (index == IndexLookup.EMPTY_INT) {
                    // Should NOT be here: Packets with unknown index should be sent with vanilla CustomPayloadPacket.
                    throw new AssertionError("Identifier " + type + " is unknown.");
                }
                buf.writeVarInt(index);

                StreamCodec<? super FriendlyByteBuf, @NotNull CustomPacketPayload> codec = getCodec(type);
                if (codec != null) {
                    codec.encode(buf, packet.payload());
                } else {
                    // Should NOT be here: Packets with known index should be known by vanilla CustomPayloadPacket.
                    throw new AssertionError("Identifier " + type + " is unknown.");
                }
            }

            @Override
            public IndexPacket decode(FriendlyByteBuf buf) {
                Identifier type = IndexLookup.getInstance().getType(buf.readVarInt());

                StreamCodec<? super FriendlyByteBuf, @NotNull CustomPacketPayload> codec = getCodec(type);
                if (codec != null) {
                    CustomPacketPayload payload;
                    try {
                        payload = codec.decode(buf);
                    } catch (RuntimeException e) {
                        throw new RuntimeException("Failed to encode custom payload: " + type, e);
                    }

                    return new IndexPacket(packetType, payload);
                } else {
                    int i = buf.readableBytes();
                    if (i >= 0 && i <= 1048576) {
                        buf.skipBytes(i);
                    } else {
                        throw new IllegalArgumentException("Payload may not be larger than 1048576 bytes");
                    }

                    return new IndexPacket(packetType, new DiscardedPayload(type));
                }
            }

            @SuppressWarnings({"unchecked", "UnstableApiUsage"})
            private @Nullable StreamCodec<? super FriendlyByteBuf, @NotNull CustomPacketPayload> getCodec(Identifier type) {
                return (StreamCodec<? super FriendlyByteBuf, @NotNull CustomPacketPayload>) NetworkRegistry.getCodec(type, ConnectionProtocol.PLAY, packetType.flow());
            }
        };
    }

    @Override
    public void handle(PacketListener listener) {
        switch (listener.flow()) {
            case CLIENTBOUND ->
                    ((ClientCommonPacketListener) listener).handleCustomPayload(payload.toVanillaClientbound());
            case SERVERBOUND ->
                    ((ServerCommonPacketListener) listener).handleCustomPayload(payload.toVanillaServerbound());
        }
    }
}
