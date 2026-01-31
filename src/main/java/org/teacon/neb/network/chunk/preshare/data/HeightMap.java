package org.teacon.neb.network.chunk.preshare.data;

import it.unimi.dsi.fastutil.objects.ObjectArrays;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.NotNull;
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

    public static final StreamCodec<@NotNull ContextByteBuf, @NotNull HeightMap> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.LONG_ARRAY, HeightMap::heightmap, HeightMap::new
    );

    public static HeightMap createCache(LevelChunk chunk) {
        long[] data = new long[SIZE * TYPES.length];
        for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            int i = ObjectArrays.binarySearch(HeightMap.TYPES, entry.getKey());
            if (i >= 0) {
                long[] array = entry.getValue().getRawData();
                if (array.length != SIZE) {
                    throw new AssertionError();
                }
                System.arraycopy(array, 0, data, SIZE * i, SIZE);
            }
        }
        return new HeightMap(data);
    }

    public record Diff(long[] heightmap) {
        public static final StreamCodec<@NotNull FriendlyByteBuf, @NotNull Diff> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.LONG_ARRAY, Diff::heightmap, Diff::new
        );

        public static Diff from(LevelChunk chunk, HeightMap base) {
            long[] data = new long[base.heightmap.length];
            for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
                int i = ObjectArrays.binarySearch(HeightMap.TYPES, entry.getKey());
                if (i >= 0) {
                    long[] array = entry.getValue().getRawData();
                    if (array.length != SIZE) {
                        throw new AssertionError();
                    }
                    VectorSupport.xor(array, 0, base.heightmap, SIZE * i, data, SIZE * i, SIZE);
                }
            }
            return new Diff(data);
        }

        public Map<Heightmap.Types, long[]> apply(HeightMap base) {
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
