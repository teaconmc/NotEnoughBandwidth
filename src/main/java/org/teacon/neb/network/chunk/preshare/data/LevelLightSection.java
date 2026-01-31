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
            lights.add(createSection(lightEngine, SectionPos.of(pos, i + lightEngine.getMinLightSection())));
        }

        return lights;
    }

    private static LevelLightSection createSection(LevelLightEngine lightEngine, SectionPos pos) {
        DataLayer block = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
        DataLayer sky = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(pos);
        return new LevelLightSection(
                block != null ? block.getData() : new byte[2048],
                sky != null ? sky.getData() : new byte[2048]
        );
    }
}
