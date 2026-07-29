package org.teacon.neb.mixin;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.teacon.neb.utils.ScopedArrayAllocator;

@Mixin(ClientboundLevelChunkPacketData.class)
public class ClientboundLevelChunkPacketDataMixin {
    // MixinExtras hasn't implemented WrapOperation command on array creation.
    //
    // @Definition(id = "byte", type = byte.class)
    // @Expression("new byte[?]")
    // @WrapOperation(
    //         method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V",
    //         at = @At("MIXINEXTRAS:EXPRESSION")
    // )
    // private static byte[] allocateArray(int length) {
    //     return ScopedArrayAllocator.allocateUninitialized(byte[].class, length);
    // }

    @Definition(id = "clone", method = "[J.clone()Ljava/lang/Object;")
    @Expression("?.clone()")
    @Redirect(
            method = "lambda$new$1",
            at = @At("MIXINEXTRAS:EXPRESSION")
    )
    private static Object cloneArray(long[] instance) {
        long[] array = ScopedArrayAllocator.allocateUninitialized(long[].class, instance.length);
        System.arraycopy(instance, 0, array, 0, instance.length);
        return array;
    }

    @Unique // Known by coermod
    private static byte[] nebw$coremod$allocateArray(int length) {
        return ScopedArrayAllocator.allocateUninitialized(byte[].class, length);
    }
}
