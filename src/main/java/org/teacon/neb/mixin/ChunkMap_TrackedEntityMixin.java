package org.teacon.neb.mixin;

import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.teacon.neb.network.TypedPacket;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public class ChunkMap_TrackedEntityMixin {
    @Shadow
    @Final
    private Entity entity;

    @ModifyVariable(
            method = {
                    "sendToTrackingPlayers",
                    "sendToTrackingPlayersAndSelf",
                    "sendToTrackingPlayersFiltered"
            },
            at = @At("HEAD"), argsOnly = true
    )
    private Packet<?> markPacket(Packet<?> packet) {
        return packet instanceof TypedPacket<?> ? packet : new TypedPacket<>(packet, "entity=" + entity.getType().toShortString());
    }

}
