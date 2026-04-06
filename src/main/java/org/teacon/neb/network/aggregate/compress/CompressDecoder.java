package org.teacon.neb.network.aggregate.compress;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.Packet;
import org.teacon.neb.NotEnoughBandwidth;
import org.teacon.neb.network.aggregate.CompressedPacket;
import org.teacon.neb.profiler.ProfilerChannel;
import org.teacon.neb.profiler.Snapshot;
import org.teacon.neb.utils.vm.LookupAccess;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.List;

@ChannelHandler.Sharable
public final class CompressDecoder extends MessageToMessageDecoder<CompressedPacket> {
    public static final String ID = NotEnoughBandwidth.id("compressed_decoder").toString();

    public static final CompressDecoder INSTANCE = new CompressDecoder();

    private static final MethodHandle DECODE;
    private static final VarHandle PROTOCOL_INFO;

    static {
        try {
            DECODE = LookupAccess.IMPL_LOOKUP.findVirtual(
                    PacketDecoder.class, "decode", MethodType.methodType(void.class, ChannelHandlerContext.class, ByteBuf.class, List.class)
            );
            PROTOCOL_INFO = LookupAccess.IMPL_LOOKUP.findVarHandle(PacketDecoder.class, "protocolInfo", ProtocolInfo.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private CompressDecoder() {
    }

    @Override
    protected void decode(ChannelHandlerContext context, CompressedPacket msg, List<Object> out) {
        PacketDecoder<?> decoder = (PacketDecoder<?>) context.pipeline().get("decoder");
        ProtocolInfo<?> protocolInfo = (ProtocolInfo<?>) PROTOCOL_INFO.get(decoder);
        if (protocolInfo.id() != ConnectionProtocol.PLAY) {
            throw new AssertionError("CompressDecoder should only be enabled in PLAY connection state.");
        }

        Snapshot snapshot = ProfilerChannel.prepareSnapshot(false, protocolInfo.flow());
        ByteBuf buf = CompressContext.get(context).decompress(msg.buf());
        while (buf.readableBytes() != 0) {
            int length = VarInt.read(buf);
            ByteBuf packet = buf.slice(buf.readerIndex(), length).readerIndex(0).writerIndex(length);

            int size = out.size();
            try {
                DECODE.invokeExact(decoder, context, packet, out);
            } catch (Throwable t2) {
                throw t2 instanceof RuntimeException re ? re : new RuntimeException(t2);
            }

            if (packet.readerIndex() != packet.capacity()) {
                throw new AssertionError("PacketDecoder should consume all bytes, or throw an exception.");
            }
            buf.skipBytes(length);

            switch (out.size() - size) {
                case 0 -> {
                }
                case 1 -> {
                    if (snapshot != null) {
                        snapshot.put((Packet<?>) out.getLast(), packet.writerIndex());
                    }
                }
                default -> throw new AssertionError("PacketDecoder should only push one packet.");
            }
        }

        if (snapshot != null) {
            snapshot.publish(buf.writerIndex(), msg.buf().writerIndex());
        }

        buf.release();
        msg.buf().release();
    }
}
