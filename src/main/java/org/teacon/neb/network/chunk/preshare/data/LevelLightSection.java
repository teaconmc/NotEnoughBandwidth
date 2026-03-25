package org.teacon.neb.network.chunk.preshare.data;

import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.neoforged.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.teacon.neb.utils.vm.VectorSupport;

import java.util.ArrayList;
import java.util.List;

public record LevelLightSection(
        byte[] block, byte[] sky
) {
    public static final StreamCodec<FriendlyByteBuf, LevelLightSection> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BYTE_ARRAY, LevelLightSection::block,
            ByteBufCodecs.BYTE_ARRAY, LevelLightSection::sky,
            LevelLightSection::new
    );

    public static List<LevelLightSection> create(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        LevelLightEngine lightEngine = chunk.getLevel().getLightEngine();

        int lightSectionCount = lightEngine.getLightSectionCount();
        List<LevelLightSection> lights = new ArrayList<>(lightSectionCount);
        for (int i = 0; i < lightSectionCount; i++) {
            SectionPos sectionPos = SectionPos.of(pos, i + lightEngine.getMinLightSection());
            lights.add(new LevelLightSection(
                    createDataLayer(lightEngine, sectionPos, LightLayer.BLOCK, true),
                    createDataLayer(lightEngine, sectionPos, LightLayer.SKY, true)
            ));
        }

        return lights;
    }

    private static final byte[] BYTES_2048 = new byte[2048];

    @Contract(value = "_, _, _, true -> !null")
    private static byte @Nullable [] createDataLayer(LevelLightEngine lightEngine, SectionPos pos, LightLayer type, boolean allocateNull) {
        DataLayer data = lightEngine.getLayerListener(type).getDataLayerData(pos);
        if (data == null) {
            if (allocateNull) {
                if (!FMLEnvironment.isProduction() && !VectorSupport.isEmpty(BYTES_2048)) {
                    throw new AssertionError("Cannot reuse BYTES_2048: it has been altered!");
                }
                return BYTES_2048;
            } else {
                return null;
            }
        }

        byte[] val = data.getData();
        if (val.length != 2048) {
            throw new AssertionError(String.format("LightLayer should be 2048 bytes, but found %d bytes.", val.length));
        }
        return val;
    }

    public record Diff(
            byte[] block, byte[] sky
    ) {
        public static final StreamCodec<FriendlyByteBuf, Diff> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE_ARRAY, Diff::block,
                ByteBufCodecs.BYTE_ARRAY, Diff::sky,
                Diff::new
        );

        public static List<Diff> from(List<LevelLightSection> bases, LevelChunk chunk) {
            ChunkPos pos = chunk.getPos();
            LevelLightEngine lightEngine = chunk.getLevel().getLightEngine();

            int lightSectionCount = lightEngine.getLightSectionCount();
            if (bases.size() != lightSectionCount) {
                throw new AssertionError(String.format("Invalid base, expecting %d LevelLightSections, but found %d.", lightSectionCount, bases.size()));
            }

            List<Diff> lights = new ArrayList<>(lightSectionCount);
            for (int i = 0; i < lightSectionCount; i++) {
                SectionPos sectionPos = SectionPos.of(pos, i + lightEngine.getMinLightSection());
                LevelLightSection base = bases.get(i);

                lights.add(new Diff(
                        diff(createDataLayer(lightEngine, sectionPos, LightLayer.BLOCK, false), base.block),
                        diff(createDataLayer(lightEngine, sectionPos, LightLayer.SKY, false), base.sky)
                ));
            }

            return lights;
        }

        private static byte[] diff(byte @Nullable [] left, byte[] right) {
            if (left == null) {
                return right.clone();
            }

            byte[] bytes = new byte[2048];
            VectorSupport.xor(left, 0, right, 0, bytes, 0, 2048);
            return bytes;
        }
    }
}
