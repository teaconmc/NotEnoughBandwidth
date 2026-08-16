package org.teacon.neb.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public interface VanillaCustomPayload extends IReleasablePacket {
    CustomPacketPayload payload();

    @Override
    default void release(ReleaseContext context) {
        IReleasablePacket.releaseIfPossible(payload());
    }
}
