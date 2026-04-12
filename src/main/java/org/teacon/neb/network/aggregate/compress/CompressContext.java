package org.teacon.neb.network.aggregate.compress;

import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.connection.ConnectionUtils;
import org.teacon.neb.NEBConfigs;
import org.teacon.neb.NotEnoughBandwidth;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.Objects;

public final class CompressContext implements AutoCloseable {
    private static final int THRESHOLD = 160;

    private static final Cleaner CLEANER = Cleaner.create();

    private static final AttributeKey<CompressContext> CONTEXT_ACCESSOR = AttributeKey.valueOf(NotEnoughBandwidth.id("compress_context").toString());

    public static CompressContext get(ChannelHandlerContext context) {
        Attribute<CompressContext> attribute = context.channel().attr(CONTEXT_ACCESSOR);
        CompressContext cc = attribute.get();
        if (cc == null) {
            synchronized (CompressContext.class) {
                cc = attribute.get();
                if (cc == null) {
                    cc = ofConnection(context);
                    attribute.set(cc);
                }
            }
        }
        return cc;
    }

    private final ZstdCompressCtx compress;
    private final ZstdDecompressCtx decompress;

    @SuppressWarnings({"unused", "FieldCanBeLocal"}) // Keep a reference only.
    private final Cleaner.Cleanable cleanable;

    private static CompressContext ofConnection(ChannelHandlerContext context) {
        ZstdCompressCtx compress = new ZstdCompressCtx()
                .setLevel(Zstd.defaultCompressionLevel())
                .setChecksum(false)
                .setMagicless(true)
                .setContentSize(false);
        ZstdDecompressCtx decompress = new ZstdDecompressCtx()
                .setMagicless(true);

        if (ConnectionUtils.getConnection(context).getSending() == PacketFlow.CLIENTBOUND) {
            compress.setWindowLog(NEBConfigs.COMPRESS_WINDOW_SIZE_LOG.get());
        }

        return new CompressContext(compress, decompress);
    }

    public static CompressContext ofPresharedChunk() {
        ZstdCompressCtx compress = new ZstdCompressCtx()
                .setLevel(NEBConfigs.PRESHARED_CHUNK_COMPRESS_LEVEL.get())
                .setChecksum(false)
                .setMagicless(true)
                .setContentSize(false);
        ZstdDecompressCtx decompress = new ZstdDecompressCtx()
                .setMagicless(true);

        return new CompressContext(compress, decompress);
    }

    private CompressContext(ZstdCompressCtx compress, ZstdDecompressCtx decompress) {
        this.compress = compress;
        this.decompress = decompress;

        // TODO: We use Cleaner to close unused context for now. Maybe use a better implementation instead?
        this.cleanable = CLEANER.register(this, new CleanableImpl(compress::close, decompress::close));
    }

    private record CleanableImpl(Runnable... targets) implements Runnable {
        @Override
        public void run() {
            for (Runnable target : targets) {
                target.run();
            }
        }
    }

    public void compress(ByteBuf original, ByteBuf target) {
        int size = original.readableBytes();

        if (size <= THRESHOLD) {
            VarInt.write(target, 0);
            target.writeBytes(original);
        } else {
            VarInt.write(target, size);

            int compressedSize = Math.toIntExact(Zstd.compressBound(size) + 1);
            target.ensureWritable(compressedSize);

            ByteBuf o2 = null, t2 = null;
            if (!original.isDirect()) {
                o2 = original.alloc().directBuffer(size, size);
                o2.writeBytes(original, size);
            }
            if (!target.isDirect()) {
                t2 = target.alloc().directBuffer(compressedSize, compressedSize);
            }

            int realSize = compress0(Objects.requireNonNullElse(o2, original), Objects.requireNonNullElse(t2, target));
            if (o2 != null) {
                o2.release();
            } else {
                original.skipBytes(size);
            }
            if (t2 != null) {
                t2.writerIndex(realSize);
                target.writeBytes(t2, realSize);
                t2.release();
            } else {
                target.writerIndex(target.writerIndex() + realSize);
            }
        }
    }

    public ByteBuf decompress(ByteBuf compressed) {
        int size = VarInt.read(compressed);
        if (size == 0) {
            return compressed.readBytes(compressed.readableBytes());
        }

        int s2;
        ByteBuf original = compressed.alloc().directBuffer(size, size);
        if (compressed.isDirect()) {
            s2 = decompress0(compressed, original);
            compressed.skipBytes(compressed.readableBytes());
        } else {
            int remain = compressed.readableBytes();
            ByteBuf direct = compressed.alloc().directBuffer(remain, remain);
            compressed.readBytes(direct, remain);

            s2 = decompress0(direct, original);
            direct.release();
        }

        if (size != s2) {
            throw new IllegalStateException("Size mismatched!");
        }

        original.writerIndex(size);
        return original;
    }

    private synchronized int compress0(ByteBuf from, ByteBuf to) {
        ByteBuffer target = to.nioBuffer(to.writerIndex(), to.writableBytes());
        if (!compress.compressDirectByteBufferStream(target, from.nioBuffer(), EndDirective.FLUSH)) {
            throw new AssertionError();
        }
        return target.position();
    }

    private synchronized int decompress0(ByteBuf from, ByteBuf to) {
        ByteBuffer target = to.nioBuffer(to.writerIndex(), to.writableBytes());
        if (!decompress.decompressDirectByteBufferStream(target, from.nioBuffer()) && target.position() != target.limit()) {
            throw new AssertionError();
        }
        return target.position();
    }

    @Override
    public void close() {
        this.cleanable.clean();
    }
}
