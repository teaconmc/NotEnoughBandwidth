package org.teacon.neb.network.aggregate.compress;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;
import net.minecraft.CrashReport;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.SkipPacketException;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.teacon.neb.NotEnoughBandwidth;
import org.teacon.neb.network.aggregate.CompressedPacket;
import org.teacon.neb.profiler.ProfilerChannel;
import org.teacon.neb.profiler.Snapshot;
import org.teacon.neb.utils.vm.LookupAccess;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;

@ChannelHandler.Sharable
public final class CompressDecoder extends MessageToMessageDecoder<CompressedPacket> {
    private static final Logger LOGGER = LoggerFactory.getLogger(CompressDecoder.class);

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

    private static final ScopedValue<Boolean> IS_DELEGATE = ScopedValue.newInstance();

    public static void onDecodeSingle(PacketDecoder<?> decoder, Packet<?> packet, int size) {
        if (IS_DELEGATE.isBound() || packet instanceof CompressedPacket) {
            return;
        }

        Snapshot snapshot = ProfilerChannel.prepareSnapshot(false, ((ProtocolInfo<?>) PROTOCOL_INFO.get(decoder)).flow());
        if (snapshot != null) {
            snapshot.put(packet, size);
            snapshot.publish(size, size);
        }
    }

    @Override
    protected void decode(ChannelHandlerContext context, CompressedPacket msg, List<Object> out) {
        PacketDecoder<?> decoder = (PacketDecoder<?>) context.pipeline().get("decoder");
        ProtocolInfo<?> protocolInfo = (ProtocolInfo<?>) PROTOCOL_INFO.get(decoder);
        if (protocolInfo.id() != ConnectionProtocol.PLAY) {
            throw new AssertionError("CompressDecoder should only be enabled in PLAY connection state.");
        }

        List<Throwable> exceptions;
        try {
            exceptions = ScopedValue.where(IS_DELEGATE, true).call(() -> decode(context, msg, out, protocolInfo, decoder));
        } catch (Throwable t) {
            LOGGER.error("FATAL: SHOULD NOT BE HERE.", t);
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                server.delayCrash(new CrashReport("FATAL: SHOULD NOT BE HERE", t));
            }
            throw new AssertionError("FATAL: SHOULD NOT BE HERE.", t);
        }

        if (!exceptions.isEmpty()) {
            DecoderException exception = new DecoderException("Cannot decode the following packets.");
            for (Throwable throwable : exceptions) {
                exception.addSuppressed(throwable);
            }
            throw exception;
        }
    }

    @NonNull
    private List<Throwable> decode(ChannelHandlerContext context, CompressedPacket msg, List<Object> out, ProtocolInfo<?> protocolInfo, PacketDecoder<?> decoder) {
        Snapshot snapshot = ProfilerChannel.prepareSnapshot(false, protocolInfo.flow());
        ByteBuf buf = CompressContext.get(context).decompress(msg.buf());
        List<Throwable> exceptions = new ArrayList<>();
        while (buf.readableBytes() != 0) {
            int length = VarInt.read(buf);
            ByteBuf packet = buf.slice(buf.readerIndex(), length).readerIndex(0).writerIndex(length);
            buf.skipBytes(length);

            int size = out.size();
            try {
                DECODE.invokeExact(decoder, context, packet, out);
                if (packet.readerIndex() != packet.capacity()) {
                    throw new AssertionError("Vanilla PacketDecoder should consume all bytes, or throw an exception.");
                }
            } catch (Throwable t2) {
                if (!(t2 instanceof SkipPacketException)) {
                    exceptions.add(t2);
                }
                for (int i = 0; i < out.size() - size; i++) {
                    out.removeLast();
                }
                continue;
            }

            if (out.size() - size != 1) {
                throw new AssertionError("PacketDecoder should only push one packet.");
            }
            if (snapshot != null) {
                snapshot.put((Packet<?>) out.getLast(), packet.writerIndex());
            }
        }

        if (snapshot != null) {
            snapshot.publish(buf.writerIndex(), msg.buf().writerIndex());
        }

        buf.release();
        msg.buf().release();
        return exceptions;
    }
}
