package org.teacon.neb.network.chunk.preshare.data;

import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.teacon.neb.utils.ContextByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public record SectionInstance(
        LevelChunkSection chunk,
        Lock lock
) {
    public static final StreamCodec<ContextByteBuf, SectionInstance> STREAM_CODEC = StreamCodec.composite(
            new StreamCodec<>() {
                @Override
                public LevelChunkSection decode(ContextByteBuf buffer) {
                    LevelChunkSection section = new LevelChunkSection(buffer.getPalettedContainerFactory());
                    section.read(buffer);
                    return section;
                }

                @Override
                public void encode(ContextByteBuf buffer, LevelChunkSection value) {
                    value.write(buffer);
                }
            }, SectionInstance::chunk,
            chunk -> new SectionInstance(chunk, new ReentrantLock())
    );

    public static List<SectionInstance> createSectionsCache(LevelChunk chunk) {
        List<SectionInstance> sections = new ArrayList<>();
        for (LevelChunkSection chunkSection : chunk.getSections()) {
            sections.add(new SectionInstance(chunkSection.copy(), new ReentrantLock()));
        }
        return sections;
    }

    public record Diff(
            PalettedContainerChange<BlockState> states,
            PalettedContainerChange<Holder<Biome>> biomes
    ) {
        public static final StreamCodec<FriendlyByteBuf, Diff> STREAM_CODEC = StreamCodec.composite(
                PalettedContainerChange.getCodec(), Diff::states,
                PalettedContainerChange.getCodec(), Diff::biomes,
                Diff::new
        );

        public static List<Diff> from(List<SectionInstance> bases, LevelChunk chunk) {
            LevelChunkSection[] chunkSections = chunk.getSections();
            if (chunkSections.length != bases.size()) {
                throw new AssertionError(String.format("Invalid base, expecting %d sections, but found %d sections.", chunkSections.length, bases.size()));
            }

            List<Diff> sections = new ArrayList<>(chunkSections.length);
            for (int i = 0; i < chunkSections.length; i++) {
                SectionInstance base = bases.get(i);
                LevelChunkSection section = chunkSections[i];

                sections.add(new Diff(
                        PalettedContainerChange.from(base.lock, base.chunk.getStates(), section.getStates()),
                        PalettedContainerChange.from(base.lock, base.chunk.getBiomes(), section.getBiomes())
                ));
            }

            return sections;
        }

        public static byte[] apply(List<SectionInstance> bases, List<Diff> diffs) {
            int length = bases.size();
            if (diffs.size() != length) {
                throw new AssertionError(String.format("Invalid diff, expecting %d sections, but found %d sections.", bases.size(), diffs.size()));
            }

            int bufferLength = 0;
            List<LevelChunkSection> sections = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                SectionInstance base = bases.get(i);
                Diff diff = diffs.get(i);

                LevelChunkSection section = new LevelChunkSection(
                        diff.states.apply(base.lock, base.chunk.getStates()),
                        diff.biomes.apply(base.lock, base.chunk.getBiomes())
                );
                bufferLength += section.getSerializedSize();
                sections.add(section);
            }

            byte[] data = new byte[bufferLength];

            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
            buffer.writerIndex(0);
            for (LevelChunkSection section : sections) {
                section.write(buffer);
            }

            return data;
        }
    }
}
