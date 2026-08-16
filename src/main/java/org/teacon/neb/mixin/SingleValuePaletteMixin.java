package org.teacon.neb.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.SingleValuePalette;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(SingleValuePalette.class) // FIXME: dirty implementation
public class SingleValuePaletteMixin<T> {
    @Shadow
    private @Nullable T value;

    @WrapMethod(method = "write")
    private void write(FriendlyByteBuf buffer, IdMap<T> globalMap, Operation<Void> original) {
        if (this.value == null) {
            buffer.writeVarInt(0);
        } else {
            original.call(buffer, globalMap);
        }
    }

    @WrapMethod(method = "copy")
    private Palette<T> copy(Operation<Palette<T>> original) {
        if (this.value == null) {
            return new SingleValuePalette<>(List.of());
        } else {
            return original.call();
        }
    }
}
