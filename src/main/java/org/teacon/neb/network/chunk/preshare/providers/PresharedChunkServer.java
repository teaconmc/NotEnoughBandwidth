package org.teacon.neb.network.chunk.preshare.providers;

import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import org.teacon.neb.NotEnoughBandwidth;
import org.teacon.neb.network.chunk.preshare.PresharedChunkVersionPacket;
import org.teacon.neb.network.chunk.preshare.grid.PresharedChunkSource;
import org.teacon.neb.network.chunk.preshare.grid.PresharedChunksIO;
import org.teacon.neb.network.chunk.preshare.grid.repos.PresharedChunkLocalSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

@EventBusSubscriber
public class PresharedChunkServer {
    private static final AttributeKey<LongList> FORCE_VANILLA_CHUNKS = AttributeKey.newInstance(NotEnoughBandwidth.id("force_vanilla_chunks").toString());

    @Nullable
    private static String sourceVersion = null;

    @Nullable
    private static PresharedChunkSource source = null;

    @SubscribeEvent
    private static void on(ServerStartedEvent event) throws IOException {
        MinecraftServer server = event.getServer();
        Path directory = server.getWorldPath(new LevelResource("preshared-chunks"));
        if (!Files.isDirectory(directory)) {
            return;
        }

        source = new PresharedChunkSource(
                server.registryAccess(),
                PresharedChunksIO.ofExecutorService("Server Chunk Decompressor [Native]"),
                new PresharedChunkLocalSource(directory)
        );
        sourceVersion = Files.readString(PresharedChunkLocalSource.resolveIndex(directory), StandardCharsets.UTF_8);
    }

    @SubscribeEvent
    private static void on(ServerStoppingEvent event) {
        if (source != null) {
            source.close();
            source = null;
        }
    }

    @SubscribeEvent
    private static void on(RegisterConfigurationTasksEvent event) {
        if (sourceVersion == null) {
            return;
        }

        event.register(new ICustomConfigurationTask() {
            private static final Type TYPE = new Type(NotEnoughBandwidth.id("preshared_chunk_version"));

            @Override
            public void run(@NonNull Consumer<CustomPacketPayload> sender) {
                sender.accept(new PresharedChunkVersionPacket(sourceVersion));
                event.getListener().finishCurrentTask(TYPE);
            }

            @Override
            public @NonNull Type type() {
                return TYPE;
            }
        });
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
