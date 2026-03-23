package org.teacon.neb.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.BandwidthDebugMonitor;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.UnconfiguredPipelineHandler;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.teacon.neb.network.NetworkManager;
import org.teacon.neb.network.aggregate.compress.CompressDecoder;
import org.teacon.neb.network.aggregate.compress.CompressEncoder;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * @author USS_Shenzhou
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Shadow
    private int sentPackets;

    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
    private void onSendPacket(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        Connection self = (Connection) (Object) this;
        if (packet.isTerminal()) {
            NetworkManager.release(self);
        } else if (listener == null && NetworkManager.onSendPacket(self, packet)) {
            this.sentPackets++;
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        NetworkManager.tick((Connection) (Object) this);
    }

    @Inject(method = "configureSerialization", at = @At("TAIL"))
    private static void onConfigureSerialization(ChannelPipeline pipeline, PacketFlow flow, boolean memoryOnly, BandwidthDebugMonitor monitor, CallbackInfo ci) {
        if (pipeline.get("encoder") instanceof PacketEncoder<?>) {
            pipeline.addAfter("encoder", CompressEncoder.ID, CompressEncoder.INSTANCE);
        }

        if (pipeline.get("decoder") instanceof PacketDecoder<?>) {
            pipeline.addAfter("decoder", CompressDecoder.ID, CompressDecoder.INSTANCE);
        }
    }

    @ModifyExpressionValue(method = "setupOutboundProtocol", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/UnconfiguredPipelineHandler;setupOutboundProtocol(Lnet/minecraft/network/ProtocolInfo;)Lnet/minecraft/network/UnconfiguredPipelineHandler$OutboundConfigurationTask;"
    ))
    private UnconfiguredPipelineHandler.OutboundConfigurationTask onSetupOutboundProtocol(
            UnconfiguredPipelineHandler.OutboundConfigurationTask original,
            @Local(index = 1, argsOnly = true) ProtocolInfo<?> protocolInfo
    ) {
        return original.andThen(context -> {
            context.pipeline().addAfter("encoder", CompressEncoder.ID, CompressEncoder.INSTANCE);

            if (protocolInfo.id() == ConnectionProtocol.PLAY) {
                NetworkManager.enable((Connection) (Object) this);
            }
        });
    }

    @ModifyExpressionValue(method = "setupInboundProtocol", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/UnconfiguredPipelineHandler;setupInboundProtocol(Lnet/minecraft/network/ProtocolInfo;)Lnet/minecraft/network/UnconfiguredPipelineHandler$InboundConfigurationTask;"
    ))
    private UnconfiguredPipelineHandler.InboundConfigurationTask onSetupInboundProtocol(
            UnconfiguredPipelineHandler.InboundConfigurationTask original
    ) {
        return original.andThen(context -> {
            context.pipeline().addAfter("decoder", CompressDecoder.ID, CompressDecoder.INSTANCE);
        });
    }

    @Unique
    private static final VarHandle nebw$CHANNEL;

    static {
        try {
            nebw$CHANNEL = MethodHandles.lookup().findVarHandle(Connection.class, "channel", Channel.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Redirect(method = "channelActive", at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/network/Connection;channel:Lio/netty/channel/Channel;",
            opcode = Opcodes.PUTFIELD
    ))
    private void publishChannel(Connection instance, Channel value) {
        nebw$CHANNEL.setVolatile(instance, value);
    }

    @ModifyExpressionValue(method = "channel", at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/network/Connection;channel:Lio/netty/channel/Channel;",
            opcode = Opcodes.GETFIELD
    ))
    private Channel readChannel(Channel channel) {
        if (channel == null) {
            channel = (Channel) nebw$CHANNEL.getVolatile((Connection) (Object) this);
        }
        return channel;
    }
}
