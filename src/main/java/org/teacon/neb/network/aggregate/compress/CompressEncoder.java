package org.teacon.neb.network.aggregate.compress;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import org.teacon.neb.NotEnoughBandwidth;
import org.teacon.neb.network.TypedPacket;
import org.teacon.neb.network.aggregate.CompressedPacket;
import org.teacon.neb.profiler.ProfilerChannel;
import org.teacon.neb.profiler.Snapshot;
import org.teacon.neb.utils.vm.LookupAccess;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.List;

@ChannelHandler.Sharable
public final class CompressEncoder extends MessageToMessageEncoder<CompressEncoder.CompressedTransfer> {
    public static final String ID = NotEnoughBandwidth.id("compressed_encoder").toString();

    public static final CompressEncoder INSTANCE = new CompressEncoder();

    private static final MethodHandle ENCODE;

    static {
        try {
            ENCODE = LookupAccess.IMPL_LOOKUP.findVirtual(
                    PacketEncoder.class, "encode", MethodType.methodType(void.class, ChannelHandlerContext.class, Packet.class, ByteBuf.class)
            );
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private CompressEncoder() {
    }

    public record CompressedTransfer(PacketType<CompressedPacket> type, List<Packet<?>> packets) {
    }

    @Override
    protected void encode(ChannelHandlerContext context, CompressedTransfer transfer, List<Object> out) {
        PacketEncoder<?> encoder = (PacketEncoder<?>) context.pipeline().get("encoder");
        if (encoder.getProtocolInfo().id() != ConnectionProtocol.PLAY) {
            throw new AssertionError("CompressEncoder should only be enabled in PLAY connection state.");
        }

        Snapshot snapshot = ProfilerChannel.prepareSnapshot(true, encoder.getProtocolInfo().flow());
        ByteBuf buf = context.alloc().directBuffer(), temp = context.alloc().directBuffer();

        for (Packet<?> packet : transfer.packets()) {
            ByteBuf t = temp.duplicate();
            try {
                Packet<?> inner = packet instanceof TypedPacket<?> tp ? tp.packet() : packet;
                ENCODE.invokeExact(encoder, context, inner, t);
            } catch (Throwable t2) {
                throw t2 instanceof RuntimeException re ? re : new RuntimeException(t2);
            }

            int size = t.writerIndex();
            if (snapshot != null) {
                snapshot.put(packet, size);
            }
            VarInt.write(buf, size);
            buf.writeBytes(t);
        }

        CompressContext.get(context).compress(buf, temp);
        if (snapshot != null) {
            snapshot.publish(buf.writerIndex(), temp.writerIndex());
        }
        try {
            ENCODE.invokeExact(encoder, context, (Packet<?>) new CompressedPacket(transfer.type(), temp), buf);
        } catch (Throwable t2) {
            throw t2 instanceof RuntimeException re ? re : new RuntimeException(t2);
        }

        temp.release();
        out.add(buf);
    }
}
