package org.teacon.neb.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.teacon.neb.network.chunk.cache.CachedChunkDebugOverlay;
import org.teacon.neb.network.chunk.preshare.providers.PresharedChunkClient;

import java.io.IOException;
import java.io.UncheckedIOException;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Shadow
    @Final
    private RegistryAccess.Frozen registryAccess;

    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void afterLevelChunkWithLight(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        CachedChunkDebugOverlay.mark(
                ChunkPos.pack(packet.getX(), packet.getZ()),
                CachedChunkDebugOverlay.STATE_RECEIVE_CHUNK
        );
    }

    @Inject(method = "handleLogin", at = @At("HEAD"))
    private void beforeLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        try {
            PresharedChunkClient.load(registryAccess);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
