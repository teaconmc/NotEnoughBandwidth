package org.teacon.neb.network.chunk.preshare.data;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import org.teacon.neb.network.chunk.preshare.PresharedChunkServer;
import org.teacon.neb.network.chunk.preshare.packets.PresharedChunkPacket;
import org.teacon.neb.utils.ScopedArrayAllocator;

import java.util.List;

public record PresharedChunk(
        ChunkPos pos,
        HeightMap heightmaps,
        List<SectionInstance> sections,
        List<LevelLightSection> lights,
        Int2ObjectMap<BlockEntityInfo> blockEntities
) {
    public static PresharedChunk createCache(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        HeightMap heightmaps = HeightMap.createCache(chunk);
        List<SectionInstance> sections = SectionInstance.createSectionsCache(chunk);
        List<LevelLightSection> lights = LevelLightSection.create(chunk);
        Int2ObjectMap<BlockEntityInfo> blockEntities = BlockEntityInfo.createBlockEntitiesCache(chunk);

        return new PresharedChunk(pos, heightmaps, sections, lights, blockEntities);
    }

    public PresharedChunkPacket createDiff(LevelChunk chunk) {
        ScopedArrayAllocator.Scope scope = PresharedChunkServer.ARRAY_ALLOCATOR.newScope();
        return scope.call(() -> {
            ChunkPos pos = chunk.getPos();
            HeightMap.Diff heightmaps = HeightMap.Diff.from(this.heightmaps, chunk);
            List<SectionInstance.Diff> sections = SectionInstance.Diff.from(this.sections, chunk);
            List<LevelLightSection.Diff> lights = LevelLightSection.Diff.from(this.lights, chunk);
            List<BlockEntityInfo.Diff> blockEntities = BlockEntityInfo.Diff.from(this.blockEntities, chunk);
            return new PresharedChunkPacket(scope, pos, heightmaps, sections, lights, blockEntities);
        });
    }
}
