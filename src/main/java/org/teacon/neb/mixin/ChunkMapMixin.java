package org.teacon.neb.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.teacon.neb.network.chunk.CachedChunkTrackingView;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow
    @Final
    private ServerLevel level;

    /**
     * @author Burning_TNT
     * @reason NEB overwrites original chunk map update strategy.
     */
    @Overwrite
    private void updateChunkTracking(ServerPlayer player) {
        if (player.level() != this.level) {
            return;
        }

        CachedChunkTrackingView.onUpdateChunkTracking(
                player, getPlayerViewDistance(player),
                pos -> markChunkPendingToSend(player, pos),
                pos -> dropChunk(player, pos)
        );
    }

    @Shadow
    protected abstract int getPlayerViewDistance(@NotNull ServerPlayer player);

    @Shadow
    protected abstract void markChunkPendingToSend(ServerPlayer player, @NotNull ChunkPos pos);

    @Shadow
    private static void dropChunk(ServerPlayer player, ChunkPos pos) {
    }
}
