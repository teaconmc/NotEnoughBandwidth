package org.teacon.neb.network.chunk.preshare.data;

import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.teacon.neb.network.chunk.preshare.data.palette.PaletteContainerAccess;
import org.teacon.neb.utils.ContextByteBuf;
import org.teacon.neb.utils.vm.LookupAccess;

import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;

public final class LevelChunkIO {
    private LevelChunkIO() {
    }

    public static List<LevelChunkSection> read(ContextByteBuf buffer) {
        short size = buffer.readShort();
        if (size == 0) {
            return List.of();
        }

        List<LevelChunkSection> sections = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            sections.add(new LevelChunkSection(buffer.getPalettedContainerFactory()));
        }
        read0(sections, buffer);
        return sections;
    }

    public static void write(ContextByteBuf buffer, List<LevelChunkSection> sections) {
        buffer.writeShort(sections.size());
        if (!sections.isEmpty()) {
            write0(buffer, sections);
        }
    }

    private static final VarHandle NON_EMPTY_BLOCK_COUNT, FLUID_COUNT;

    static {
        try {
            NON_EMPTY_BLOCK_COUNT = LookupAccess.IMPL_LOOKUP.findVarHandle(LevelChunkSection.class, "nonEmptyBlockCount", short.class).withInvokeExactBehavior();
            FLUID_COUNT = LookupAccess.IMPL_LOOKUP.findVarHandle(LevelChunkSection.class, "fluidCount", short.class).withInvokeExactBehavior();
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void read0(List<LevelChunkSection> sections, ContextByteBuf buffer) {
        for (LevelChunkSection section : sections) {
            NON_EMPTY_BLOCK_COUNT.set(section, buffer.readShort());
            FLUID_COUNT.set(section, buffer.readShort());

            PaletteContainerAccess.setData(section.getStates(), PaletteContainerAccess.allocateDataFrom(section.getStates(), buffer.readByte()));
            PaletteContainerAccess.setData(section.getBiomes(), PaletteContainerAccess.allocateDataFrom(section.getBiomes(), buffer.readByte()));
        }

        IdMap<BlockState> blockStatesGlobalMap = buffer.getPalettedContainerFactory().blockStatesStrategy().globalMap();
        for (LevelChunkSection section : sections) {
            PaletteContainerAccess.getPalette(section.getStates()).read(buffer, blockStatesGlobalMap);
        }
        IdMap<Holder<Biome>> biomeGlobalMap = buffer.getPalettedContainerFactory().biomeStrategy().globalMap();
        for (LevelChunkSection section : sections) {
            PaletteContainerAccess.getPalette(section.getBiomes()).read(buffer, biomeGlobalMap);
        }

        for (LevelChunkSection section : sections) {
            buffer.readFixedSizeLongArray(PaletteContainerAccess.getBitStorage(section.getStates()).getRaw());
        }
        for (LevelChunkSection section : sections) {
            buffer.readFixedSizeLongArray(PaletteContainerAccess.getBitStorage(section.getBiomes()).getRaw());
        }
    }

    private static void write0(ContextByteBuf buffer, List<LevelChunkSection> sections) {
        for (LevelChunkSection section : sections) {
            buffer.writeShort((short) NON_EMPTY_BLOCK_COUNT.get(section));
            buffer.writeShort((short) FLUID_COUNT.get(section));
            buffer.writeByte(PaletteContainerAccess.getBitStorage(section.getStates()).getBits());
            buffer.writeByte(PaletteContainerAccess.getBitStorage(section.getBiomes()).getBits());
        }

        IdMap<BlockState> blockStatesGlobalMap = buffer.getPalettedContainerFactory().blockStatesStrategy().globalMap();
        for (LevelChunkSection section : sections) {
            PaletteContainerAccess.getPalette(section.getStates()).write(buffer, blockStatesGlobalMap);
        }
        IdMap<Holder<Biome>> biomeGlobalMap = buffer.getPalettedContainerFactory().biomeStrategy().globalMap();
        for (LevelChunkSection section : sections) {
            PaletteContainerAccess.getPalette(section.getBiomes()).write(buffer, biomeGlobalMap);
        }

        for (LevelChunkSection section : sections) {
            buffer.writeFixedSizeLongArray(PaletteContainerAccess.getBitStorage(section.getStates()).getRaw());
        }
        for (LevelChunkSection section : sections) {
            buffer.writeFixedSizeLongArray(PaletteContainerAccess.getBitStorage(section.getBiomes()).getRaw());
        }
    }
}
