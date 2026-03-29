package org.teacon.neb.network.chunk.preshare;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.teacon.neb.network.chunk.preshare.data.BlockEntityInfo;
import org.teacon.neb.network.chunk.preshare.data.HeightMap;
import org.teacon.neb.network.chunk.preshare.data.LevelLightSection;
import org.teacon.neb.network.chunk.preshare.data.SectionInstance;
import org.teacon.neb.utils.ContextByteBuf;

import java.util.List;

public record PresharedChunk(
        Identifier level,
        ChunkPos pos,
        HeightMap heightmaps,
        List<SectionInstance> sections,
        List<LevelLightSection> lights,
        Int2ObjectMap<BlockEntityInfo> blockEntities
) {
    public static final StreamCodec<ContextByteBuf, PresharedChunk> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC, PresharedChunk::level,
            ChunkPos.STREAM_CODEC, PresharedChunk::pos,
            HeightMap.STREAM_CODEC, PresharedChunk::heightmaps,
            SectionInstance.STREAM_CODEC, PresharedChunk::sections,
            LevelLightSection.STREAM_CODEC.apply(ByteBufCodecs.list()), PresharedChunk::lights,
            BlockEntityInfo.BLOCK_CODEC, PresharedChunk::blockEntities,
            PresharedChunk::new
    );

    public static PresharedChunk createCache(LevelChunk chunk) {
        Identifier level = chunk.getLevel().dimension().identifier();
        ChunkPos pos = chunk.getPos();
        HeightMap heightmaps = HeightMap.createCache(chunk);
        List<SectionInstance> sections = SectionInstance.createSectionsCache(chunk);
        List<LevelLightSection> lights = LevelLightSection.create(chunk);
        Int2ObjectMap<BlockEntityInfo> blockEntities = BlockEntityInfo.createBlockEntitiesCache(chunk);

        return new PresharedChunk(level, pos, heightmaps, sections, lights, blockEntities);
    }

    public PresharedChunkPacket createDiff(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        HeightMap.Diff heightmaps = HeightMap.Diff.from(chunk, this.heightmaps);
        List<SectionInstance.Diff> sections = SectionInstance.Diff.from(this.sections, chunk);
        List<LevelLightSection.Diff> lights = LevelLightSection.Diff.from(this.lights, chunk);
        List<BlockEntityInfo.Diff> blockEntities = BlockEntityInfo.Diff.from(this.blockEntities, chunk);
        return new PresharedChunkPacket(pos, heightmaps, sections, lights, blockEntities);
    }
}
