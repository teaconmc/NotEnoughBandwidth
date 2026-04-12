package org.teacon.neb.utils;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.NonNull;

public record GridPos(int x, int z) {
    public static final int GRID_SIZE = 16;

    public static final StreamCodec<ByteBuf, GridPos> STREAM_CODEC = new StreamCodec<>() {
        public GridPos decode(ByteBuf input) {
            return unpack(input.readLong());
        }

        public void encode(ByteBuf output, GridPos value) {
            output.writeLong(value.pack());
        }
    };

    public static GridPos fromChunk(ChunkPos pos) {
        return new GridPos(Math.floorDiv(pos.x(), GRID_SIZE), Math.floorDiv(pos.z(), GRID_SIZE));
    }

    public static GridPos unpack(long key) {
        return new GridPos((int) key, (int) (key >> 32));
    }

    public long pack() {
        return pack(this.x, this.z);
    }

    public static long pack(int x, int z) {
        return x & 4294967295L | (z & 4294967295L) << 32;
    }

    public static int getX(long pos) {
        return (int) (pos & 4294967295L);
    }

    public static int getZ(long pos) {
        return (int) (pos >>> 32 & 4294967295L);
    }

    @Override
    public int hashCode() {
        return hash(this.x, this.z);
    }

    public static int hash(int x, int z) {
        int xTransform = 1664525 * x + 1013904223;
        int zTransform = 1664525 * (z ^ -559038737) + 1013904223;
        return xTransform ^ zTransform;
    }

    @Override
    public @NonNull String toString() {
        return "[" + this.x + ", " + this.z + "]";
    }
}
