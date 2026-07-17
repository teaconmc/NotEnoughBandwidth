package org.teacon.neb.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Varint21FrameDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.teacon.neb.network.NetworkManager;

@Mixin(Varint21FrameDecoder.class)
public class Varint21FrameDecoderMixin {
    @Unique
    private static final ScopedValue<Integer> MAX_LENGTH = ScopedValue.newInstance();

    @WrapOperation(
            method = "decode",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/Varint21FrameDecoder;copyVarint(Lio/netty/buffer/ByteBuf;Lio/netty/buffer/ByteBuf;)Z"
            )
    )
    private boolean maxLength(ByteBuf in, ByteBuf out, Operation<Boolean> original, @Local(ordinal = 0) ChannelHandlerContext ctx) {
        return ScopedValue.where(MAX_LENGTH, NetworkManager.getMaxFrameVarintSize(ctx))
                .call(() -> original.call(in, out));
    }

    @ModifyConstant(
            method = "copyVarint",
            constant = @Constant(intValue = 3)
    )
    private static int maxLength(int constant) {
        return MAX_LENGTH.get();
    }
}
