package org.teacon.neb.network;

import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import org.jspecify.annotations.NonNull;

public record TypedPacket<T extends PacketListener>(Packet<T> packet, String extra) implements Packet<T> {
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
