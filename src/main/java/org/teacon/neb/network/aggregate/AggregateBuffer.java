package org.teacon.neb.network.aggregate;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.teacon.neb.NotEnoughBandwidth;
import org.teacon.neb.network.aggregate.compress.CompressEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class AggregateBuffer {
    private final Queue<Packet<?>> buffer = new ConcurrentLinkedQueue<>();
    private final Queue<ChannelFutureListener> listeners = new ConcurrentLinkedQueue<>();
    private final Connection connection;

    public AggregateBuffer(Connection connection) {
        this.connection = connection;
    }

    private static final AttributeKey<AggregateBuffer> BUFFER = AttributeKey.valueOf(NotEnoughBandwidth.id("buffer").toString());

    private static Attribute<AggregateBuffer> accessAB(Connection connection) {
        return connection.channel().attr(BUFFER);
    }

    public static void initialize(Connection connection) {
        AggregateBuffer current = Objects.requireNonNull(accessAB(connection)).setIfAbsent(new AggregateBuffer(connection));
        if (current != null && !current.buffer.isEmpty()) {
            throw new IllegalStateException("Packets in the buffer has been sent!");
        }
    }

    public static void release(Connection connection) {
        AggregateBuffer current = accessAB(connection).getAndSet(null);
        if (current != null) {
            current.flush();
        }
    }

    @Nullable
    public static AggregateBuffer get(Connection connection) {
        return accessAB(connection).get();
    }

    public void push(Packet<?> packet) {
        this.buffer.add(packet);
    }

    public void push(ChannelFutureListener listener) {
        this.listeners.add(listener);
    }

    public void flush() {
        ChannelFuture future = flushPackets();

        ChannelFutureListener listener;
        while ((listener = listeners.poll()) != null) {
            future.addListener(listener);
        }
    }

    private ChannelFuture flushPackets() {
        if (buffer.isEmpty()) { // Should NOT be here regularly, but we can handle it anyway.
            return flushPackets(List.of());
        }

        ChannelFuture last = null;
        while (true) {
            List<Packet<?>> packets = new ArrayList<>(200);

            Packet<?> packet = null;
            while (packets.size() < 200 && (packet = buffer.poll()) != null) {
                packets.add(packet);
            }

            if (!packets.isEmpty()) {
                last = flushPackets(packets);
            }

            if (packet == null) {
                return last != null ? last : flushPackets(List.of());
            }
        }
    }

    private ChannelFuture flushPackets(List<Packet<?>> packets) {
        return this.connection.channel().writeAndFlush(new CompressEncoder.CompressedTransfer(switch (this.connection.getSending()) {
            case CLIENTBOUND -> CompressedPacket.C_TYPE;
            case SERVERBOUND -> CompressedPacket.S_TYPE;
        }, packets));
    }
}
