package org.teacon.neb.network.chunk.preshare;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.teacon.neb.network.chunk.preshare.data.BlockEntityInfo;
import org.teacon.neb.network.chunk.preshare.data.HeightMap;
import org.teacon.neb.network.chunk.preshare.data.LevelLightSection;
import org.teacon.neb.network.chunk.preshare.data.SectionInstance;
import org.teacon.neb.utils.ContextByteBuf;

import java.util.List;

public final class PresharedChunksIO {
    private PresharedChunksIO() {
    }

    public static Long2ObjectMap<PresharedChunk> read(ContextByteBuf buffer) {
        long[] poses = buffer.readLongArray();
        Long2ObjectMap<PresharedChunk> value = new Long2ObjectOpenHashMap<>(poses.length);

        for (long pos : poses) {
            HeightMap heightmaps = HeightMap.STREAM_CODEC.decode(buffer);
            List<SectionInstance> sections = SectionInstance.STREAM_CODEC.decode(buffer);
            List<LevelLightSection> lights = LevelLightSection.STREAM_CODEC.decode(buffer);
            Int2ObjectMap<BlockEntityInfo> blockEntities = BlockEntityInfo.BLOCK_CODEC.decode(buffer);
            value.put(pos, new PresharedChunk(
                    Level.OVERWORLD.identifier(), ChunkPos.unpack(pos),
                    heightmaps, sections, lights, blockEntities
            ));
        }
        return value;
    }

    public static void write(Long2ObjectMap<PresharedChunk> value, ContextByteBuf buffer) {
        long[] poses = new long[value.size()];
        LongIterator iterator = value.keySet().iterator();
        for (int i = 0; i < poses.length; i++) {
            poses[i] = iterator.nextLong();
        }

        buffer.writeLongArray(poses);
        for (long pose : poses) {
            PresharedChunk chunk = value.get(pose);
            HeightMap.STREAM_CODEC.encode(buffer, chunk.heightmaps());
            SectionInstance.STREAM_CODEC.encode(buffer, chunk.sections());
            LevelLightSection.STREAM_CODEC.encode(buffer, chunk.lights());
            BlockEntityInfo.BLOCK_CODEC.encode(buffer, chunk.blockEntities());
        }
    }
}
