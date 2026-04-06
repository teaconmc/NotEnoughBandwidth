package org.teacon.neb.network;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import org.teacon.neb.network.indexed.IndexPacket;

public record TypedPacket<T extends PacketListener>(Packet<T> packet, String packetType) implements Packet<T> {
    public static String computeType(Packet<?> packet) {
        final Identifier packetID = packet.type().id();

        return switch (packet) {
            case VanillaCustomPayload payload -> payload.payload().type().id().toString();

            case TypedPacket(Packet<?> _, String packetType) -> packetType;

            case IndexPacket(PacketType<IndexPacket> _, CustomPacketPayload payload) -> payload.type().id().toString();

            case ClientboundBlockEntityDataPacket entityData -> {
                Identifier location = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entityData.getType());
                if (location != null) {
                    yield packetID + "[type=" + location + "]";
                } else {
                    yield packetID.toString();
                }
            }

            default -> packetID.toString();
        };
    }

    @Override
    public @NonNull PacketType<? extends Packet<T>> type() {
        return packet.type();
    }

    @Override
    public void handle(T listener) {
        packet.handle(listener);
    }

    @Override
    public boolean isSkippable() {
        return packet.isSkippable();
    }

    @Override
    public boolean isTerminal() {
        return packet.isTerminal();
    }
}
