package org.teacon.neb.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Frustum.class)
public class FrustumMixin { // FIX: MC-306810
    @Shadow
    private Vector4f viewVector;

    @Inject(method = "offsetToFullyIncludeCameraCube", at = @At("HEAD"), cancellable = true)
    private void beforeOffsetToFullyIncludeCameraCube(int cubeSize, CallbackInfoReturnable<Frustum> cir) {
        if (Float.isNaN(this.viewVector.x()) || Float.isNaN(this.viewVector.y())) {
            cir.setReturnValue((Frustum) (Object) this);
        }
    }
}
