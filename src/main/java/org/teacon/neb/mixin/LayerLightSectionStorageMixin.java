package org.teacon.neb.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.teacon.neb.utils.FastSynchronizedMap;

@Mixin(LayerLightSectionStorage.class)
public class LayerLightSectionStorageMixin {
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMaps;synchronize(Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;)Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;"
            )
    )
    private <V> Long2ObjectMap<V> fastSynchronize(Long2ObjectMap<V> map) {
        return new FastSynchronizedMap<>(map);
    }
}
