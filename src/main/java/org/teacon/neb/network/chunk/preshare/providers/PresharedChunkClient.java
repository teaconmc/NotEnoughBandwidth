package org.teacon.neb.network.chunk.preshare.providers;

import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.teacon.neb.network.chunk.preshare.PresharedChunkBundle;

import java.io.IOException;
import java.util.UUID;

@EventBusSubscriber(Dist.CLIENT)
public class PresharedChunkClient {
    public static volatile PresharedChunkBundle lookup = PresharedChunkBundle.EMPTY;

    public static volatile UUID requestedVersion = PresharedChunkBundle.EMPTY.getVersion();

    public static void load(Connection connection, RegistryAccess registryAccess) throws IOException {
        if (connection.isMemoryConnection()) {
            PresharedChunkClient.lookup = PresharedChunkServer.lookup;
        } else {
            UUID requestedVersion = PresharedChunkClient.requestedVersion;
            if (PresharedChunkBundle.EMPTY.getVersion().equals(requestedVersion)) {
                return;
            }

            PresharedChunkBundle bundle = PresharedChunkBundle.load(
                    Minecraft.getInstance().gameDirectory.toPath().resolve("preshared-chunks/" + requestedVersion + ".neb"),
                    registryAccess
            );

            UUID localVersion = bundle.getVersion();
            if (!localVersion.equals(requestedVersion)) {
                connection.disconnect(Component.translatable("neb.preshared.bundle_missing", requestedVersion.toString()));
            } else {
                PresharedChunkClient.lookup = bundle;
            }
        }
    }

    @SubscribeEvent
    private static void on(ClientPlayerNetworkEvent.LoggingOut event) {
        lookup = PresharedChunkBundle.EMPTY;
        requestedVersion = PresharedChunkBundle.EMPTY.getVersion();
    }
}
