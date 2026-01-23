package org.teacon.neb.network.aggregate;

import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.teacon.neb.NotEnoughBandwidth;
import org.teacon.neb.network.aggregate.compress.CompressEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class AggregateBuffer {
    private final Queue<Packet<?>> buffer = new ConcurrentLinkedQueue<>();
    private long lastTickTime = 0;

    private final Connection connection;

    public AggregateBuffer(Connection connection) {
        this.connection = connection;
    }

    private static final AttributeKey<AggregateBuffer> BUFFER = AttributeKey.valueOf(NotEnoughBandwidth.id("buffer").toString());

    private static Attribute<AggregateBuffer> accessAB(Connection connection) {
        Channel channel = connection.channel();
        if (channel == null) {
            return null;
        }
        return channel.attr(BUFFER);
    }

    public static void initialize(Connection connection) {
        AggregateBuffer current = accessAB(connection).setIfAbsent(new AggregateBuffer(connection));
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

    public static AggregateBuffer get(Connection connection) {
        return accessAB(connection).get();
    }

    public void push(Packet<?> packet) {
        this.buffer.add(packet);
    }

    public void flush() {
        if (buffer.isEmpty()) {
            return;
        }

        while (true) {
            List<Packet<?>> packets = new ArrayList<>(200);

            Packet<?> packet = null;
            while (packets.size() < 200 && (packet = buffer.poll()) != null) {
                packets.add(packet);
            }

            if (!packets.isEmpty()) {
                this.connection.channel().writeAndFlush(new CompressEncoder.CompressedTransfer(switch (this.connection.getSending()) {
                    case CLIENTBOUND -> CompressedPacket.C_TYPE;
                    case SERVERBOUND -> CompressedPacket.S_TYPE;
                }, packets));
            }

            if (packet == null) {
                break;
            }
        }
    }
}
