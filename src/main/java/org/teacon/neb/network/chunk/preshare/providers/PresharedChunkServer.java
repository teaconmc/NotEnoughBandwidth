package org.teacon.neb.network.chunk.preshare.providers;

import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
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
import org.teacon.neb.NEBConfigs;
import org.teacon.neb.NotEnoughBandwidth;
import org.teacon.neb.network.chunk.preshare.PresharedChunkVersionPacket;
import org.teacon.neb.network.chunk.preshare.grid.PresharedChunkSource;
import org.teacon.neb.network.chunk.preshare.grid.PresharedChunksIO;
import org.teacon.neb.network.chunk.preshare.grid.repos.PresharedChunkLocalSource;
import org.teacon.neb.utils.GridPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@EventBusSubscriber
public class PresharedChunkServer {
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
                event.getServer(),
                server.registryAccess(),
                PresharedChunksIO.ofExecutorService("Server Chunk Decompressor [Native]"),
                List.of(new PresharedChunkLocalSource(directory))
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

    /// grid_pos
    private static final AttributeKey<long[]> FORCE_VANILLA_CHUNKS = AttributeKey.newInstance(NotEnoughBandwidth.id("force_vanilla_chunks").toString());

    public static void markForceVanillaChunk(Connection connection, ChunkPos pos) {
        Attribute<long[]> attr = connection.channel().attr(FORCE_VANILLA_CHUNKS);
        long[] chunks = attr.get();
        if (chunks == null) {
            attr.set(chunks = PresharedRejection.allocate());
        }
        PresharedRejection.markRejected(chunks, GridPos.fromChunk(pos).pack());
    }

    public static PresharedChunkSource.IResult makePacket(Connection connection, LevelChunk chunk) {
        if (chunk.getLevel().dimension() != Level.OVERWORLD || source == null) {
            return PresharedChunkSource.Empty.INSTANCE;
        }

        long[] chunks = connection.channel().attr(FORCE_VANILLA_CHUNKS).get();
        if (chunks != null && PresharedRejection.isRejected(chunks, GridPos.fromChunk(chunk.getPos()).pack())) {
            return PresharedChunkSource.Empty.INSTANCE;
        }

        return source.load(chunk.getPos().pack(), true);
    }

    private static final class PresharedRejection {
        private static final int MAX = 9;

        public static long[] allocate() {
            return new long[MAX * 2];
        }

        public static void markRejected(long[] values, long gridXZ) {
            if (values[0] != gridXZ) {
                int i = 1;
                for (; i < MAX - 1; i++) {
                    if (values[i * 2] == gridXZ) {
                        break;
                    }
                }

                System.arraycopy(values, 0, values, 2, i * 2);
                values[0] = gridXZ;
            }

            values[1] = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(NEBConfigs.PRESHARED_CHUNK_RETRY_TIMEOUT.get() + 2);
        }

        public static boolean isRejected(long[] values, long gridXZ) {
            for (int i = 0; i < MAX; i++) {
                if (values[i * 2 + 1] < System.currentTimeMillis()) {
                    break;
                }
                if (values[i * 2] == gridXZ) {
                    return true;
                }
            }
            return false;
        }
    }
}
