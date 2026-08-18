package org.teacon.neb.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.world.LevelChunkAuxiliaryLightManager;
import net.neoforged.neoforge.network.payload.AuxiliaryLightDataPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.teacon.neb.network.chunk.preshare.PresharedChunkServer;
import org.teacon.neb.utils.vm.LookupAccess;

import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.Map;

@SuppressWarnings("UnstableApiUsage")
@Mixin(PlayerChunkSender.class)
public class PlayerChunkSenderMixin {
    @Unique
    private static final VarHandle NEOFORGE_LIGHTS;

    static {
        try {
            NEOFORGE_LIGHTS = LookupAccess.IMPL_LOOKUP.findVarHandle(LevelChunkAuxiliaryLightManager.class, "lights", Map.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * @author Burning_TNT
     * @reason NEB overwrites original chunk map update strategy.
     */
    @Overwrite
    private static void sendChunk(ServerGamePacketListenerImpl connection, ServerLevel level, LevelChunk chunk) {
        ProfilerFiller profiler = Profiler.get();

        Packet<? super ClientGamePacketListener> packet = PresharedChunkServer.sendChunk(connection, level, chunk, profiler);
        if (packet == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        Map<BlockPos, Byte> lights = (Map<BlockPos, Byte>) NEOFORGE_LIGHTS.get(chunk.getAuxLightManager(chunk.getPos()));
        connection.send(new ClientboundBundlePacket(List.of(packet, new AuxiliaryLightDataPayload(chunk.getPos(), lights).toVanillaClientbound())));
        level.debugSynchronizers().startTrackingChunk(connection.player, chunk.getPos());
        net.neoforged.neoforge.event.EventHooks.fireChunkSent(connection.player, chunk, level);
    }
}
