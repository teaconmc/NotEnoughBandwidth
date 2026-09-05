package org.teacon.neb.utils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufUtil;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ContextByteBuf extends RegistryFriendlyByteBuf {
    private final PalettedContainerFactory palettedContainerFactory;

    public ContextByteBuf(ByteBuf source, RegistryAccess registryAccess, ConnectionType connectionType) {
        super(source, registryAccess, connectionType);
        this.palettedContainerFactory = PalettedContainerFactory.create(registryAccess);
    }

    public PalettedContainerFactory getPalettedContainerFactory() {
        return palettedContainerFactory;
    }

    public static Path dump(ByteBuf buffer) throws IOException {
        Path path = Files.createTempFile("neb-packet-", ".bin");
        try (InputStream is = new ByteBufInputStream(buffer.slice(), false);
             OutputStream os = Files.newOutputStream(path)) {
            is.transferTo(os);
        }
        return path;
    }
}
