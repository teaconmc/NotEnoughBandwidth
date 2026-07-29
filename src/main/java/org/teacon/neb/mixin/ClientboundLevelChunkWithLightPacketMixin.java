package org.teacon.neb.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.teacon.neb.network.IReleasablePacket;
import org.teacon.neb.network.chunk.preshare.PresharedChunkServer;
import org.teacon.neb.utils.ScopedArrayAllocator;

import java.util.BitSet;

@Mixin(ClientboundLevelChunkWithLightPacket.class)
public class ClientboundLevelChunkWithLightPacketMixin implements IReleasablePacket {
    @Unique
    private ScopedArrayAllocator.Scope scope;

    @Inject(
            method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/lighting/LevelLightEngine;Ljava/util/BitSet;Ljava/util/BitSet;)V",
            at = @At("HEAD")
    )
    private static void beforeConstruct(
            CallbackInfo ci,
            @Share("scope") LocalRef<ScopedArrayAllocator.Scope> scope
    ) {
        scope.set(PresharedChunkServer.ARRAY_ALLOCATOR.newScope());
    }

    @WrapOperation(
            method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/lighting/LevelLightEngine;Ljava/util/BitSet;Ljava/util/BitSet;)V",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/world/level/chunk/LevelChunk;)Lnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;"
            )
    )
    private static ClientboundLevelChunkPacketData onConstructChunkData(
            LevelChunk levelChunk,
            Operation<ClientboundLevelChunkPacketData> original,
            @Share("scope") LocalRef<ScopedArrayAllocator.Scope> scope
    ) {
        return scope.get().call(() -> original.call(levelChunk));
    }

    @WrapOperation(
            method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/lighting/LevelLightEngine;Ljava/util/BitSet;Ljava/util/BitSet;)V",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/lighting/LevelLightEngine;Ljava/util/BitSet;Ljava/util/BitSet;)Lnet/minecraft/network/protocol/game/ClientboundLightUpdatePacketData;"
            )
    )
    private static ClientboundLightUpdatePacketData onConstructLightData(
            ChunkPos chunkPos,
            LevelLightEngine lightEngine,
            BitSet skyChangedLightSectionFilter,
            BitSet blockChangedLightSectionFilter,
            Operation<ClientboundLightUpdatePacketData> original,
            @Share("scope") LocalRef<ScopedArrayAllocator.Scope> scope
    ) {
        return scope.get().call(() -> original.call(chunkPos, lightEngine, skyChangedLightSectionFilter, blockChangedLightSectionFilter));
    }

    @Inject(
            method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/lighting/LevelLightEngine;Ljava/util/BitSet;Ljava/util/BitSet;)V",
            at = @At("RETURN")
    )
    private void afterConstruct(
            CallbackInfo ci,
            @Share("scope") LocalRef<ScopedArrayAllocator.Scope> scope
    ) {
        this.scope = scope.get();
    }

    @Override
    public void release(ReleaseContext context) {
        if (this.scope != null) {
            this.scope.close();
        }
    }
}
