package org.teacon.neb.network.chunk.preshare.providers;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.teacon.neb.NotEnoughBandwidth;
import org.teacon.neb.network.chunk.preshare.PresharedChunkBundle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.UUID;

@EventBusSubscriber(modid = NotEnoughBandwidth.MODID, value = Dist.CLIENT)
public class PresharedChunkClient {
    public static volatile PresharedChunkBundle lookup = PresharedChunkBundle.EMPTY;

    public static UUID readVersion() {
        try {
            return PresharedChunkBundle.loadVersion(getBundlePath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SubscribeEvent
    private static void on(ClientPlayerNetworkEvent.LoggingIn event) throws IOException {
        lookup = PresharedChunkBundle.load(getBundlePath(), event.getPlayer().registryAccess());
    }

    @SubscribeEvent
    private static void on(ClientPlayerNetworkEvent.LoggingOut event) {
        lookup = PresharedChunkBundle.EMPTY;
    }

    private static Path getBundlePath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("preshared-chunks.neb");
    }
}
