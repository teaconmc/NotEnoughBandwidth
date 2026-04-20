package org.teacon.neb.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.teacon.neb.network.chunk.debug.ClientChunkDebugOverlay;

@Mixin(Gui.class)
public class GuiMixin {
    @Inject(method = "extractDebugOverlay", at = @At("TAIL"))
    private void afterExtractDebugOverlay(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        ClientChunkDebugOverlay.render(graphics);
    }
}
