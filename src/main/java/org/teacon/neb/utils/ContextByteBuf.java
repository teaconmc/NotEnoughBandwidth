package org.teacon.neb.utils;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.neoforged.neoforge.network.connection.ConnectionType;

public final class ContextByteBuf extends RegistryFriendlyByteBuf {
    private final PalettedContainerFactory palettedContainerFactory;

    public ContextByteBuf(ByteBuf source, RegistryAccess registryAccess, ConnectionType connectionType) {
        super(source, registryAccess, connectionType);
        this.palettedContainerFactory = PalettedContainerFactory.create(registryAccess);
    }

    public PalettedContainerFactory getPalettedContainerFactory() {
        return palettedContainerFactory;
    }
}
