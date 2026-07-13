package org.teacon.neb.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.teacon.neb.network.aggregate.compress.CompressDecoder;

@Mixin(PacketDecoder.class)
public class PacketDecoderMixin {
    @ModifyExpressionValue(
            method = "decode",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/codec/StreamCodec;decode(Ljava/lang/Object;)Ljava/lang/Object;"
            )
    )
    private Object afterDecodePacket(Object original, @Local(index = 4) int readableBytes) {
        CompressDecoder.onDecodeSingle((PacketDecoder<?>) (Object) this, (Packet<?>) original, readableBytes);
        return original;
    }
}
