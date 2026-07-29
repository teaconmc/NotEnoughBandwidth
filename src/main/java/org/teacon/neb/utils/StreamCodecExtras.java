package org.teacon.neb.utils;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import org.jspecify.annotations.NonNull;

public final class StreamCodecExtras {
    private StreamCodecExtras() {
    }

    public interface Wrapper<B extends ByteBuf> {
        B wrap(B previous, ByteBuf value);
    }

    public static <B extends ByteBuf, V> StreamCodec<B, V> zeroRLE(StreamCodec<B, V> delegate, Wrapper<B> wrapper) {
        return new StreamCodec<>() {
            @Override
            public void encode(@NonNull B buffer, @NonNull V value) {
                ByteBuf source = buffer.alloc().directBuffer(4096, Integer.MAX_VALUE);
                try {
                    delegate.encode(wrapper.wrap(buffer, source), value);

                    int limit = source.writerIndex();
                    VarInt.write(buffer, limit);

                    for (int end, i = 0; i < limit; i = end) {
                        if (source.getByte(i) == 0) {
                            end = findNext(i + 1, source, false);
                            buffer.writeByte(0);
                            VarInt.write(buffer, end - i);
                        } else {
                            end = findNext(i + 1, source, true);
                            buffer.writeBytes(source, i, end - i);
                        }
                    }
                } finally {
                    source.release();
                }
            }

            @Override
            public @NonNull V decode(@NonNull B buffer) {
                ByteBuf target = buffer.alloc().directBuffer(VarInt.read(buffer));
                try {
                    while (buffer.readableBytes() > 0) {
                        byte byteValue = buffer.readByte();
                        if (byteValue == 0) {
                            target.writeZero(VarInt.read(buffer));
                        } else {
                            int index = buffer.readerIndex();
                            int end = findNext(index, buffer, true);
                            target.writeBytes(buffer, index - 1, end - index + 1);
                            buffer.readerIndex(end);
                        }
                    }

                    return delegate.decode(wrapper.wrap(buffer, target));
                } finally {
                    target.release();
                }
            }

            private int findNext(int i, ByteBuf buffer, boolean isZero) {
                for (int limit = buffer.writerIndex(); i < limit; i++) {
                    if (isZero == (buffer.getByte(i) == 0)) {
                        break;
                    }
                }
                return i;
            }
        };
    }
}
