package org.teacon.neb.network.chunk.preshare.data;

import it.unimi.dsi.fastutil.objects.ObjectArrays;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.teacon.neb.utils.ContextByteBuf;
import org.teacon.neb.utils.vm.VectorSupport;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

public record HeightMap(
        long[] heightmap
) {
    private static final Heightmap.Types[] TYPES = Arrays.stream(Heightmap.Types.values())
            .filter(Heightmap.Types::sendToClient)
            .sorted()
            .toArray(Heightmap.Types[]::new);

    private static final int SIZE = 37;

    private static final int LENGTH = SIZE * TYPES.length;
    
    private static final StreamCodec<FriendlyByteBuf, long[]> FIXED_HEIGHTMAP_ARRAY = new StreamCodec<>() {
        @Override
        public long[] decode(FriendlyByteBuf buffer) {
            long[] value = new long[LENGTH];
            buffer.readFixedSizeLongArray(value);
            return value;
        }

        @Override
        public void encode(FriendlyByteBuf buffer, long[] value) {
            if (value.length != LENGTH) {
                throw new AssertionError("Invalid heightmap array: " + value.length);
            }
            buffer.writeFixedSizeLongArray(value);
        }
    };

    public static final StreamCodec<ContextByteBuf, HeightMap> STREAM_CODEC = StreamCodec.composite(
            FIXED_HEIGHTMAP_ARRAY, HeightMap::heightmap, HeightMap::new
    );

    public static HeightMap createCache(LevelChunk chunk) {
        long[] data = new long[LENGTH];
        for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            int i = ObjectArrays.binarySearch(HeightMap.TYPES, entry.getKey());
            if (i >= 0) {
                long[] array = entry.getValue().getRawData();
                if (array.length != SIZE) {
                    throw new AssertionError(String.format("Heightmap should be %d byte long, but found %d bytes.", SIZE, array.length));
                }
                System.arraycopy(array, 0, data, SIZE * i, SIZE);
            }
        }
        return new HeightMap(data);
    }

    public record Diff(long[] heightmap) {
        public static final StreamCodec<FriendlyByteBuf, Diff> STREAM_CODEC = StreamCodec.composite(
                FIXED_HEIGHTMAP_ARRAY, Diff::heightmap, Diff::new
        );

        public static Diff from(LevelChunk chunk, HeightMap base) {
            if (base.heightmap.length != LENGTH) {
                throw new AssertionError(String.format("Invalid base, expecting %d * %d bytes, but found %d bytes.", SIZE, TYPES.length, base.heightmap.length));
            }

            long[] data = new long[base.heightmap.length];
            for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
                int i = ObjectArrays.binarySearch(HeightMap.TYPES, entry.getKey());
                if (i >= 0) {
                    long[] array = entry.getValue().getRawData();
                    if (array.length != SIZE) {
                        throw new AssertionError(String.format("Heightmap should be %d byte long, but found %d bytes.", SIZE, array.length));
                    }
                    VectorSupport.xor(array, 0, base.heightmap, SIZE * i, data, SIZE * i, SIZE);
                }
            }
            return new Diff(data);
        }

        public Map<Heightmap.Types, long[]> apply(HeightMap base) {
            if (base.heightmap.length != LENGTH) {
                throw new AssertionError(String.format("Invalid base, expecting %d * %d bytes, but found %d bytes.", SIZE, TYPES.length, base.heightmap.length));
            }
            if (heightmap.length != LENGTH) {
                throw new AssertionError(String.format("Invalid diff, expecting %d * %d bytes, but found %d bytes.", SIZE, TYPES.length, heightmap.length));
            }

            Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
            for (int i = 0; i < HeightMap.TYPES.length; i++) {
                long[] data = new long[SIZE];
                VectorSupport.xor(base.heightmap, i * SIZE, this.heightmap, i * SIZE, data, 0, data.length);
                heightmaps.put(HeightMap.TYPES[i], data);
            }

            return heightmaps;
        }
    }
}
