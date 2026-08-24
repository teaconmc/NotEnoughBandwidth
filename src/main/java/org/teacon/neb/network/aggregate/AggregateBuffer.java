package org.teacon.neb.network.aggregate;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.jetbrains.annotations.Nullable;
import org.teacon.neb.network.SidedDelegate;
import org.teacon.neb.network.aggregate.compress.CompressEncoder;
import org.teacon.neb.utils.vm.LookupAccess;

import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class AggregateBuffer {
    private static final VarHandle AGGREGATE_BUFFER;

    static {
        try {
            // noinspection JavaLangInvokeHandleSignature : This field is injected by NEB, in ConnectionMixin.java
            AGGREGATE_BUFFER = LookupAccess.IMPL_LOOKUP.findVarHandle(Connection.class, "nebw$aggregateBuffer", AggregateBuffer.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Connection connection;

    private static final int BATCH = 200;

    private List<Packet<?>> packets = new ArrayList<>(BATCH);
    private final List<ChannelFutureListener> listeners = new ArrayList<>();

    private final Queue<Packet<?>> asyncPackets = new ConcurrentLinkedQueue<>();
    private final Queue<ChannelFutureListener> asyncListeners = new ConcurrentLinkedQueue<>();

    public AggregateBuffer(Connection connection) {
        this.connection = connection;
    }

    public static void initialize(Connection connection) {
        if (!AGGREGATE_BUFFER.compareAndSet(connection, null, new AggregateBuffer(connection))) {
            throw new IllegalStateException("Packets has been sent!");
        }
    }

    public static void release(Connection connection) {
        AggregateBuffer current = (AggregateBuffer) AGGREGATE_BUFFER.getAndSet(connection, null);
        if (current != null) {
            // FIXME: What if someone actually sends such packet outside Server Thread ??!
            if (!SidedDelegate.select(connection).isSameThread()) {
                throw new IllegalStateException("Non-managed thread can't send terminal packet.");
            }

            current.flush();
        }
    }

    @Nullable
    public static AggregateBuffer get(Connection connection) {
        return (AggregateBuffer) AGGREGATE_BUFFER.getOpaque(connection);
    }

    public void push(Packet<?> packet) {
        if (SidedDelegate.select(connection).isSameThread()) {
            packets.add(packet);
            if (packets.size() == BATCH) {
                ChannelFuture future = flushSpecific(packets);
                packets = new ArrayList<>(BATCH);
                for (ChannelFutureListener listener : listeners) {
                    future.addListener(listener);
                }
                listeners.clear();
            }
        } else {
            asyncPackets.add(packet);
        }
    }

    public void push(ChannelFutureListener listener) {
        if (SidedDelegate.select(connection).isSameThread()) {
            listeners.add(listener);
        } else {
            asyncListeners.add(listener);
        }
    }

    public void flush() {
        if (!SidedDelegate.select(connection).isSameThread()) {
            throw new IllegalStateException("Cannot flush on non-managed thread.");
        }

        ChannelFuture future = flushPackets();

        for (ChannelFutureListener listener : listeners) {
            future.addListener(listener);
        }
        listeners.clear();

        ChannelFutureListener listener;
        while ((listener = asyncListeners.poll()) != null) {
            future.addListener(listener);
        }
    }

    private ChannelFuture flushPackets() {
        if (packets.isEmpty() && asyncPackets.isEmpty()) { // Should NOT be here regularly, but we can handle it anyway.
            return flushSpecific(List.of());
        }

        List<Packet<?>> buffer;
        if (packets.isEmpty()) {
            buffer = new ArrayList<>(BATCH);
        } else {
            buffer = packets;
            packets = new ArrayList<>(BATCH);
        }

        ChannelFuture last = null;
        while (true) {
            Packet<?> packet = null;
            while (buffer.size() < BATCH && (packet = asyncPackets.poll()) != null) {
                buffer.add(packet);
            }

            if (!buffer.isEmpty()) {
                last = flushSpecific(buffer);
            }

            if (packet == null) {
                return last != null ? last : flushSpecific(List.of());
            }

            buffer = new ArrayList<>(BATCH);
        }
    }

    private ChannelFuture flushSpecific(List<Packet<?>> packets) {
        return connection.channel().writeAndFlush(new CompressEncoder.CompressedTransfer(switch (connection.getSending()) {
            case CLIENTBOUND -> CompressedPacket.C_TYPE;
            case SERVERBOUND -> CompressedPacket.S_TYPE;
        }, packets));
    }
}
