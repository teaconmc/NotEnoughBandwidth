package org.teacon.neb.mixin;

import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.teacon.neb.network.IReleasablePacket;

@Mixin(BundlePacket.class)
public abstract class BundlePacketMixin<T extends PacketListener> implements IReleasablePacket {
    @Shadow
    @Final
    private Iterable<Packet<? super T>> packets;

    @Override
    public void release(ReleaseContext context) {
        for (Packet<? super T> packet : packets) {
            IReleasablePacket.releaseIfPossible(packet);
        }
    }
}
