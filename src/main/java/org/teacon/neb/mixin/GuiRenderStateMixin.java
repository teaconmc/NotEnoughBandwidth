package org.teacon.neb.mixin;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.ScreenArea;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.teacon.neb.network.chunk.cache.GuiGraphicsHandler;

import java.util.List;

@Mixin(GuiRenderState.class)
public class GuiRenderStateMixin {
    @Inject(method = "hasIntersection", at = @At("HEAD"), cancellable = true)
    private void useOverride(ScreenRectangle bounds, @Nullable List<? extends ScreenArea> states, CallbackInfoReturnable<Boolean> cir) {
        if (GuiGraphicsHandler.disableIntersectionChecks) {
            cir.setReturnValue(true);
        }
    }
}
