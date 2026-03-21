package org.teacon.neb.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.teacon.neb.network.chunk.cache.CachedChunkDebugOverlay;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void afterHandleLevelChunkWithLight(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        long state = CachedChunkDebugOverlay.encodeState(CachedChunkDebugOverlay.STATE_RECEIVE_CHUNK);
        CachedChunkDebugOverlay.states.putIfAbsent(ChunkPos.pack(packet.getX(), packet.getZ()), state);
    }
}
