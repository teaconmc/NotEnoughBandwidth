package org.teacon.neb.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.teacon.neb.network.IReleasablePacket;
import org.teacon.neb.network.aggregate.compress.CompressEncoder;

@Mixin(PacketEncoder.class)
public class PacketEncoderMixin {
    @Inject(
            method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/codec/StreamCodec;encode(Ljava/lang/Object;Ljava/lang/Object;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void afterEncodePacket(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf output, CallbackInfo ci) {
        CompressEncoder.onEncodeSingle((PacketEncoder<?>) (Object) this, packet, output.readableBytes());
        if (packet instanceof IReleasablePacket releasable) {
            releasable.release(IReleasablePacket.ReleaseContext.INSTANCE);
        }
    }
}
