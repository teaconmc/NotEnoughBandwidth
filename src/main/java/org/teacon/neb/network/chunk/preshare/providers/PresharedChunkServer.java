package org.teacon.neb.network.chunk.preshare.providers;

import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.jetbrains.annotations.Nullable;
import org.teacon.neb.NotEnoughBandwidth;
import org.teacon.neb.network.chunk.preshare.grid.PresharedChunkSource;
import org.teacon.neb.network.chunk.preshare.grid.PresharedChunksIO;
import org.teacon.neb.network.chunk.preshare.grid.repos.PresharedChunkLocalSource;

import java.nio.file.Files;
import java.nio.file.Path;

@EventBusSubscriber
public class PresharedChunkServer {
    private static final AttributeKey<LongList> FORCE_VANILLA_CHUNKS = AttributeKey.newInstance(NotEnoughBandwidth.id("force_vanilla_chunks").toString());

    @Nullable
    private static PresharedChunkSource source = null;

    @SubscribeEvent
    private static void on(ServerStartedEvent event) {
        load(event.getServer());
    }

    public static void load(MinecraftServer server) {
        Path directory = server.getWorldPath(new LevelResource("preshared-chunks"));
        if (!Files.isDirectory(directory)) {
            return;
        }

        source = new PresharedChunkSource(
                server.registryAccess(),
                PresharedChunksIO.ofExecutorService("Server Chunk Decompressor [Native]"),
                new PresharedChunkLocalSource(directory)
        );
    }

    @SubscribeEvent
    private static void on(ServerStoppingEvent event) {
        if (source != null) {
            source.close();
            source = null;
        }
    }

    public static void markForceVanillaChunk(Connection connection, ChunkPos pos) {
        Attribute<LongList> attr = connection.channel().attr(FORCE_VANILLA_CHUNKS);
        LongList chunks = attr.get();
        if (chunks == null) {
            chunks = new LongArrayList(16);
            attr.set(chunks);
        }
        chunks.add(pos.pack());
    }

    public static PresharedChunkSource.IResult makePacket(Connection connection, LevelChunk chunk) {
        if (chunk.getLevel().dimension() != Level.OVERWORLD || source == null) {
            return PresharedChunkSource.Empty.INSTANCE;
        }

        long pos = chunk.getPos().pack();

        LongList chunks = connection.channel().attr(FORCE_VANILLA_CHUNKS).get();
        if (chunks != null) {
            int index = chunks.indexOf(pos);
            if (index >= 0) {
                chunks.removeLong(index);
                return PresharedChunkSource.Empty.INSTANCE;
            }
        }

        return source.load(pos, true);
    }
}
