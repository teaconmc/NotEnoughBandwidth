package org.teacon.neb.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.teacon.neb.network.VanillaCustomPayload;
import org.teacon.neb.network.indexed.IndexPacket;

@Mixin(PacketDecoder.class)
public class PacketDecoderMixin {
    @Redirect(
            method = "decode",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/String;valueOf(Ljava/lang/Object;)Ljava/lang/String;",
                    ordinal = 1
            )
    )
    private String transformPacketType(Object _0, @Local(name = "packet") Packet<?> packet) {
        Identifier payloadType = switch (packet) {
            case VanillaCustomPayload payload -> payload.payload().type().id();
            case IndexPacket(PacketType<@NotNull IndexPacket> ignored, CustomPacketPayload payload) ->
                    payload.type().id();
            default -> null;
        };

        PacketType<? extends @NotNull Packet<?>> packetType = packet.type();
        if (payloadType == null) {
            return packetType.toString();
        } else {
            return packetType.flow().id() + "/" + packetType.id() + "#" + payloadType;
        }
    }
}
