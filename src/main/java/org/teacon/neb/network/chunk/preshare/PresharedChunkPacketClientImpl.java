package org.teacon.neb.network.chunk.preshare;

import com.google.common.collect.ImmutableMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.teacon.neb.network.chunk.preshare.data.PresharedChunk;
import org.teacon.neb.network.chunk.preshare.packets.PresharedChunkPacket;
import org.teacon.neb.network.chunk.preshare.data.BlockEntityInfo;
import org.teacon.neb.network.chunk.preshare.data.LevelLightSection;
import org.teacon.neb.network.chunk.preshare.data.SectionInstance;
import org.teacon.neb.utils.ChunkRelativePos;
import org.teacon.neb.utils.vm.LookupAccess;
import org.teacon.neb.utils.vm.VectorSupport;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

@NullMarked
public class PresharedChunkPacketClientImpl {
    private static final MethodHandle CLCPD_NEW, CLCPD_BEI_NEW, CLUPD_NEW, CLCWLP_NEW;

    private static final VarHandle PLAYER;

    static {
        try {
            CLCPD_NEW = LookupAccess.createConstructor(ClientboundLevelChunkPacketData.class, ImmutableMap.of(
                    "heightmaps", Map.class,
                    "buffer", byte[].class,
                    "blockEntitiesData", List.class
            ));
            if (!CLCPD_NEW.type().equals(
                    MethodType.methodType(ClientboundLevelChunkPacketData.class, Map.class, byte[].class, List.class)
            )) {
                throw new AssertionError();
            }

            CLCPD_BEI_NEW = LookupAccess.IMPL_LOOKUP.findConstructor(
                    Class.forName("net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData$BlockEntityInfo"),
                    MethodType.methodType(void.class, int.class, int.class, BlockEntityType.class, CompoundTag.class)
            ).asType(MethodType.methodType(Object.class, int.class, int.class, BlockEntityType.class, CompoundTag.class));

            CLUPD_NEW = LookupAccess.createConstructor(ClientboundLightUpdatePacketData.class, ImmutableMap.of(
                    "skyYMask", BitSet.class,
                    "blockYMask", BitSet.class,
                    "emptySkyYMask", BitSet.class,
                    "emptyBlockYMask", BitSet.class,
                    "skyUpdates", List.class,
                    "blockUpdates", List.class
            ));

            CLCWLP_NEW = LookupAccess.createConstructor(ClientboundLevelChunkWithLightPacket.class, ImmutableMap.of(
                    "x", int.class,
                    "z", int.class,
                    "chunkData", ClientboundLevelChunkPacketData.class,
                    "lightData", ClientboundLightUpdatePacketData.class
            ));

            PLAYER = LookupAccess.IMPL_LOOKUP.findVarHandle(Minecraft.class, "player", LocalPlayer.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static ClientboundLevelChunkWithLightPacket makeVanillaChunkPacket(PresharedChunkPacket packet, PresharedChunk base) {
        ChunkPos pos = base.pos();
        ClientboundLevelChunkPacketData chunk = applyChunk(packet, base);
        ClientboundLightUpdatePacketData light = applyLight(packet, base);

        try {
            return (ClientboundLevelChunkWithLightPacket) CLCWLP_NEW.invokeExact(pos.x(), pos.z(), chunk, light);
        } catch (Throwable e) {
            throw LookupAccess.raise(e);
        }
    }

    private static ClientboundLevelChunkPacketData applyChunk(PresharedChunkPacket packet, PresharedChunk base) {
        Map<Heightmap.Types, long[]> heightmaps = packet.heightmaps().apply(base.heightmaps());
        byte[] buffer = SectionInstance.Diff.apply(base.sections(), packet.sections());
        List<Object> blockEntities = new ArrayList<>();
        for (BlockEntityInfo info : BlockEntityInfo.Diff.apply(base.blockEntities(), packet.blockEntities())) {
            ChunkRelativePos pos = info.pos();

            try {
                blockEntities.add(CLCPD_BEI_NEW.invokeExact((pos.x() << 4) | pos.z(), (int) pos.y(), info.type(), info.data()));
            } catch (Throwable e) {
                throw LookupAccess.raise(e);
            }
        }

        try {
            return (ClientboundLevelChunkPacketData) CLCPD_NEW.invokeExact(heightmaps, buffer, blockEntities);
        } catch (Throwable e) {
            throw LookupAccess.raise(e);
        }
    }

    private static ClientboundLightUpdatePacketData applyLight(PresharedChunkPacket packet, PresharedChunk base) {
        BitSet skyYMask = new BitSet();
        BitSet blockYMask = new BitSet();
        BitSet emptySkyYMask = new BitSet();
        BitSet emptyBlockYMask = new BitSet();
        List<byte[]> skyUpdates = new ArrayList<>();
        List<byte[]> blockUpdates = new ArrayList<>();

        for (int i = 0; i < packet.lights().size(); i++) {
            LevelLightSection lightBase = base.lights().get(i);
            LevelLightSection.Diff lightDiff = packet.lights().get(i);
            applyLightType(lightDiff.block(), lightBase.block(), i, blockYMask, emptyBlockYMask, blockUpdates);
            applyLightType(lightDiff.sky(), lightBase.sky(), i, skyYMask, emptySkyYMask, skyUpdates);
        }

        try {
            return (ClientboundLightUpdatePacketData) CLUPD_NEW.invokeExact(skyYMask, blockYMask, emptySkyYMask, emptyBlockYMask, skyUpdates, blockUpdates);
        } catch (Throwable e) {
            throw LookupAccess.raise(e);
        }
    }

    private static void applyLightType(byte[] diff, byte[] base, int sectionIndex, BitSet yMask, BitSet emptyYMask, List<byte[]> updates) {
        byte[] lights = new byte[2048];
        VectorSupport.xor(diff, 0, base, 0, lights, 0, 2048);

        if (VectorSupport.isEmpty(lights)) {
            emptyYMask.set(sectionIndex);
        } else {
            yMask.set(sectionIndex);
            updates.add(lights);
        }
    }

    public static void setLocalPlayer(Minecraft minecraft, LocalPlayer player) {
        PLAYER.setVolatile(minecraft, player);
    }

    @Nullable
    static LocalPlayer getLocalPlayer() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = (LocalPlayer) PLAYER.get(minecraft);
        if (player == null) {
            player = (LocalPlayer) PLAYER.getVolatile(minecraft);
        }
        return player;
    }
}
