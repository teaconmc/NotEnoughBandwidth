package org.teacon.neb.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ProtocolSwapHandler;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.teacon.neb.network.aggregate.compress.CompressDecoder;
import org.teacon.neb.network.aggregate.compress.CompressEncoder;

@Mixin(ProtocolSwapHandler.class)
public interface ProtocolSwapHandlerMixin {
    @Inject(method = "handleInboundTerminalPacket", at = @At(
            value = "INVOKE",
            target = "Lio/netty/channel/ChannelPipeline;remove(Ljava/lang/String;)Lio/netty/channel/ChannelHandler;"))
    private static void onInboundTerminalPacket(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        context.pipeline().remove(CompressDecoder.ID);
    }

    @Inject(method = "handleOutboundTerminalPacket", at = @At(
            value = "INVOKE",
            target = "Lio/netty/channel/ChannelPipeline;remove(Ljava/lang/String;)Lio/netty/channel/ChannelHandler;"))
    private static void onOutboundTerminalPacket(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        context.pipeline().remove(CompressEncoder.ID);
    }
}
