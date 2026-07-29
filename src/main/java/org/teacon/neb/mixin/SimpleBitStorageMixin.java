package org.teacon.neb.mixin;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import net.minecraft.util.SimpleBitStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.teacon.neb.utils.ScopedArrayAllocator;

@Mixin(SimpleBitStorage.class)
public class SimpleBitStorageMixin {
    @Definition(id = "clone", method = "[J.clone()Ljava/lang/Object;")
    @Expression("?.clone()")
    @Redirect(
            method = "copy",
            at = @At("MIXINEXTRAS:EXPRESSION")
    )
    private Object clone(long[] instance) {
        long[] array = ScopedArrayAllocator.allocateUninitialized(long[].class, instance.length);
        System.arraycopy(instance, 0, array, 0, instance.length);
        return array;
    }
}
