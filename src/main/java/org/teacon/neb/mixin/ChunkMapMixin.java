package org.teacon.neb.mixin;

import com.google.common.cache.CacheBuilder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.teacon.neb.network.chunk.cache.CachedChunkTrackingView;

import java.util.Map;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow
    @Final
    private TicketStorage ticketStorage;

    @Shadow
    @Final
    private ServerLevel level;

    @Unique
    private final Map<ServerPlayer, TicketType> ticketTypes = CacheBuilder.newBuilder()
            .weakKeys()
            .<ServerPlayer, TicketType>build()
            .asMap();

    /**
     * @author Burning_TNT
     * @reason NEB overwrites original chunk map update strategy.
     */
    @Overwrite
    private void updateChunkTracking(ServerPlayer player) {
        if (player.level() != this.level) {
            return;
        }

        CachedChunkTrackingView.onUpdateChunkTracking(player, getPlayerViewDistance(player), new CachedChunkTrackingView.Context() {
            @Override
            public void sendChunk(ChunkPos pos) {
                markChunkPendingToSend(player, pos);
            }

            @Override
            public void dropChunk(ChunkPos pos) {
                ChunkMapMixin.dropChunk(player, pos);
            }

            @Override
            public void addTicket(ChunkPos pos) {
                TicketType ticketType = ticketTypes.computeIfAbsent(
                        player,
                        _ -> new TicketType(40L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION)
                );

                ticketStorage.addTicket(new Ticket(ticketType, ChunkLevel.byStatus(ChunkStatus.FULL)), pos);
            }
        });
    }

    @Shadow
    protected abstract int getPlayerViewDistance(ServerPlayer player);

    @Shadow
    protected abstract void markChunkPendingToSend(ServerPlayer player, ChunkPos pos);

    @Shadow
    private static void dropChunk(ServerPlayer player, ChunkPos pos) {
    }
}
