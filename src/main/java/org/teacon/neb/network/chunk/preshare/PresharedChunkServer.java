package org.teacon.neb.network.chunk.preshare;

import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.profiling.ProfilerFiller;
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
import org.teacon.neb.network.chunk.preshare.data.PresharedChunk;
import org.teacon.neb.network.chunk.preshare.packets.PresharedChunkVersionPacket;
import org.teacon.neb.network.chunk.preshare.repo.PresharedChunkSource;
import org.teacon.neb.network.chunk.preshare.repo.PresharedChunksIO;
import org.teacon.neb.network.chunk.preshare.repo.impl.PresharedChunkLocalSource;
import org.teacon.neb.profiler.ChunkSendingEvent;
import org.teacon.neb.profiler.ProfilerChannel;
import org.teacon.neb.utils.GridPos;
import org.teacon.neb.utils.ScopedArrayAllocator;

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

    public static final ScopedArrayAllocator ARRAY_ALLOCATOR = new ScopedArrayAllocator();

    @SubscribeEvent
    private static void on(ServerStartedEvent event) throws IOException {
        if (!NEBConfigs.PRESHARED_CHUNK_ENABLED.get()) {
            return;
        }

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

    public static void markChunkStrategy(Connection connection, ChunkPos pos, boolean forceVanilla) {
        Attribute<long[]> attr = connection.channel().attr(FORCE_VANILLA_CHUNKS);
        long[] chunks = attr.get();
        if (chunks == null) {
            attr.set(chunks = PresharedRejection.allocate());
        }

        if (forceVanilla) {
            PresharedRejection.markRejected(chunks, GridPos.fromChunk(pos).pack());
        } else {
            PresharedRejection.clearRejected(chunks, GridPos.fromChunk(pos).pack());
        }
    }

    private static final PresharedChunkSource.Empty INSTANCE_REJECTED = new PresharedChunkSource.Empty(); // FIXME: Dirty implementation!

    @Nullable
    public static Packet<? super ClientGamePacketListener> sendChunk(ServerGamePacketListenerImpl connection, ServerLevel level, LevelChunk chunk, ProfilerFiller profiler) {
        PresharedChunkSource.IResult result = lookupChunk(connection.getConnection(), chunk);
        return switch (result) {
            case PresharedChunkSource.Empty _, PresharedChunkSource.Failed _ -> {
                ProfilerChannel.SERVER.onChunkSendingEvent(
                        result == INSTANCE_REJECTED ? ChunkSendingEvent.SEND_PRESHARED_REJECTED_THEN_VANILLA : ChunkSendingEvent.SEND_VANILLA
                );
                yield new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
            }
            case PresharedChunkSource.Loaded(PresharedChunk preshared) -> {
                ProfilerChannel.SERVER.onChunkSendingEvent(ChunkSendingEvent.SEND_PRESHARED);

                profiler.push("createChunkDiff");
                ClientboundCustomPayloadPacket packet = preshared.createDiff(chunk).toVanillaClientbound();
                profiler.pop();
                yield packet;
            }
            case PresharedChunkSource.Pending pending -> {
                pending.thenRunAsync(_ -> {
                    if (connection.player.level() == level && connection.player.getChunkTrackingView().contains(chunk.getPos())) {
                        connection.chunkSender.markChunkPendingToSend(chunk);
                    }
                });
                yield null;
            }
        };
    }

    private static PresharedChunkSource.IResult lookupChunk(Connection connection, LevelChunk chunk) {
        if (chunk.getLevel().dimension() != Level.OVERWORLD || source == null) {
            return PresharedChunkSource.Empty.INSTANCE;
        }

        long[] chunks = connection.channel().attr(FORCE_VANILLA_CHUNKS).get();
        if (chunks != null && PresharedRejection.isRejected(chunks, GridPos.fromChunk(chunk.getPos()).pack())) {
            return INSTANCE_REJECTED;
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

        public static void clearRejected(long[] values, long gridXZ) {
            for (int i = 0; i < MAX; i++) {
                if (values[i * 2 + 1] < System.currentTimeMillis()) {
                    return;
                }
                if (values[i * 2] == gridXZ) {
                    values[i * 2] = GridPos.pack(Integer.MAX_VALUE, Integer.MAX_VALUE);
                    return;
                }
            }
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
