package org.teacon.neb.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Varint21LengthFieldPrepender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.teacon.neb.network.NetworkManager;

@Mixin(Varint21LengthFieldPrepender.class)
public class Varint21LengthFieldPrependerMixin {
    @ModifyConstant(
            method = "encode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Lio/netty/buffer/ByteBuf;)V",
            constant = @Constant(intValue = 3)
    )
    private int maxLength(int constant, @Local(ordinal = 0) ChannelHandlerContext ctx) {
        return NetworkManager.getMaxFrameVarintSize(ctx);
    }
}
