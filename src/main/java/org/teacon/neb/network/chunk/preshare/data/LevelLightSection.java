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
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public record LevelLightSection(
        byte[] block, byte[] sky
) {
    public static final StreamCodec<@NotNull FriendlyByteBuf, @NotNull LevelLightSection> STREAM_CODEC = StreamCodec.composite(
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
                    createDataLayer(lightEngine, sectionPos, LightLayer.BLOCK),
                    createDataLayer(lightEngine, sectionPos, LightLayer.SKY)
            ));
        }

        return lights;
    }

    private static byte[] createDataLayer(LevelLightEngine lightEngine, SectionPos pos, LightLayer type) {
        DataLayer data = lightEngine.getLayerListener(type).getDataLayerData(pos);
        if (data == null) {
            return new byte[2048];
        }

        byte[] val = data.getData();
        if (val.length != 2048) {
            throw new AssertionError(String.format("LightLayer should be 2048 bytes, but found %d bytes.", val.length));
        }
        return val;
    }
}
