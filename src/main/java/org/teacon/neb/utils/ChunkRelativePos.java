package org.teacon.neb.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;

public record ChunkRelativePos(
        @Range(from = 0, to = 16) short x,
        @Range(from = -64, to = 384) short y,
        @Range(from = 0, to = 16) short z,

        @Range(from = 0, to = 127) byte flag
) {
    public static final StreamCodec<@NotNull FriendlyByteBuf, @NotNull ChunkRelativePos> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ChunkRelativePos decode(FriendlyByteBuf input) {
            return unpack(input.readInt());
        }

        @Override
        public void encode(FriendlyByteBuf output, ChunkRelativePos value) {
            output.writeInt(value.pack());
        }
    };

    public ChunkRelativePos(BlockPos blockPos, byte flag) {
        this((short) SectionPos.sectionRelative(blockPos.getX()), (short) blockPos.getY(), (short) SectionPos.sectionRelative(blockPos.getZ()), flag);
    }

    public static ChunkRelativePos unpack(int value) {
        short x = (short) ((value >> 24) & 0xFF);
        short z = (short) ((value >> 16) & 0xFF);
        short y = (short) (value & 0x1FF);
        byte flag = (byte) ((value >> 9) & 0x7F);

        return new ChunkRelativePos(x, y, z, flag);
    }

    public int pack() {
        return pack(x, y, z, flag);
    }

    public static int pack(short x, short y, short z, byte flag) {
        return (x << 24) | (z << 16) | ((flag & 0x7F) << 9) | (y & 0x1FF);
    }

    public ChunkRelativePos withFlag(byte flag) {
        return new ChunkRelativePos(x, y, z, flag);
    }
}
