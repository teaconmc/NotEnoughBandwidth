package org.teacon.neb.mixin;

import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.teacon.neb.network.NetworkManager;

@Mixin(PacketEncoder.class)
public class PacketEncoderMixin {
    @Redirect(
            method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/Packet;type()Lnet/minecraft/network/protocol/PacketType;"
            )
    )
    private PacketType<?> transformPacketType(Packet<?> packet) {
        return NetworkManager.unwrapType(packet);
    }
}
