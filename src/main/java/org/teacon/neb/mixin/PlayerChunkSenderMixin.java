package org.teacon.neb.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
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
import org.teacon.neb.network.chunk.preshare.PresharedChunk;
import org.teacon.neb.network.chunk.preshare.PresharedChunkBundle;
import org.teacon.neb.network.chunk.preshare.providers.PresharedChunkServer;
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
        PresharedChunk preshared = PresharedChunkBundle.getChunk(PresharedChunkServer.lookup, level, chunk.getPos());
        if (preshared == null) {
            // Vanilla implementation
            connection.send(chunk.getAuxLightManager(chunk.getPos()).sendLightDataTo(
                    new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null)
            ));
        } else {
            @SuppressWarnings("unchecked")
            Map<BlockPos, Byte> lights = (Map<BlockPos, Byte>) NEOFORGE_LIGHTS.get(chunk.getAuxLightManager(chunk.getPos()));

            ProfilerFiller profiler = Profiler.get();
            profiler.push("createChunkDiff");
            connection.send(new ClientboundBundlePacket(List.of(
                    preshared.createDiff(chunk).toVanillaClientbound(),
                    new AuxiliaryLightDataPayload(chunk.getPos(), lights).toVanillaClientbound()
            )));
            profiler.pop();
        }

        level.debugSynchronizers().startTrackingChunk(connection.player, chunk.getPos());
        net.neoforged.neoforge.event.EventHooks.fireChunkSent(connection.player, chunk, level);
    }
}
