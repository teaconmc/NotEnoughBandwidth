package org.teacon.neb.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.world.level.ChunkPos;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.teacon.neb.network.chunk.debug.ChunkReceivingEvent;
import org.teacon.neb.network.chunk.preshare.PresharedChunkClient;
import org.teacon.neb.network.chunk.preshare.PresharedChunkPacketClientImpl;

import java.io.IOException;
import java.io.UncheckedIOException;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin extends ClientCommonPacketListenerImpl {
    protected ClientPacketListenerMixin(Minecraft minecraft, Connection connection, CommonListenerCookie cookie) {
        super(minecraft, connection, cookie);
    }

    @Shadow
    @Final
    private RegistryAccess.Frozen registryAccess;

    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void afterLevelChunkWithLight(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        ChunkReceivingEvent.VANILLA_RECEIVED.submit(ChunkPos.pack(packet.getX(), packet.getZ()));
    }

    @Redirect(method = {"handleLogin", "handleRespawn"}, at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD, target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private void publishLocalPlayer(Minecraft instance, LocalPlayer value) {
        PresharedChunkPacketClientImpl.setLocalPlayer(instance, value);
    }

    @Inject(method = "handleLogin", at = @At("HEAD"))
    private void beforeLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        try {
            PresharedChunkClient.handleLogin(this.connection, registryAccess);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
