package org.teacon.neb.mixin;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import net.minecraft.world.level.chunk.DataLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.teacon.neb.utils.ScopedArrayAllocator;

@Mixin(DataLayer.class)
public class DataLayerMixin {
    @Definition(id = "clone", method = "[B.clone()Ljava/lang/Object;")
    @Expression("?.clone()")
    @Redirect(
            method = "copy",
            at = @At("MIXINEXTRAS:EXPRESSION")
    )
    private Object clone(byte[] instance) {
        byte[] array = ScopedArrayAllocator.allocateUninitialized(byte[].class, instance.length);
        System.arraycopy(instance, 0, array, 0, instance.length);
        return array;
    }
}
