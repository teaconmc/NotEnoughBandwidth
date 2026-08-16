package org.teacon.neb;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongIterators;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.teacon.neb.network.chunk.preshare.repo.PresharedChunksIO;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public interface NotEnoughBandwidthAPI {
    static CompletableFuture<?> savePresharedChunks(Path directory, LongIterator iterator) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(iterator, "iterator");

        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Must be a directory.");
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalArgumentException("No Minecraft server is running!");
        }

        CompletableFuture<@Nullable Void> future = new CompletableFuture<>();
        server.schedule(server.wrapRunnable(() -> {
            PresharedChunksIO.save(directory, iterator).whenComplete((v, t) -> {
                if (t == null) {
                    future.complete(v);
                } else {
                    future.completeExceptionally(t);
                }
            });
        }));
        return future;
    }

    @EventBusSubscriber(Dist.DEDICATED_SERVER)
    final class Preset {
        private Preset() {
        }

        private static final Logger LOGGER = LoggerFactory.getLogger(Preset.class);

        @SubscribeEvent
        private static void on(ServerStartedEvent event) {
            String preset = System.getenv("NEB_GEN_PRESHARED_CHUNK_PRESET");
            if (preset == null) {
                return;
            }

            URI uri = URI.create(preset);
            Map<String, String> query = Arrays.stream(Objects.requireNonNullElse(uri.getQuery(), "").split("&"))
                    .map(s -> {
                        String[] v = s.split("=", 3);
                        if (v.length != 1 && v.length != 2) {
                            throw new IllegalArgumentException("bad query string");
                        }
                        return v;
                    })
                    .collect(Collectors.toMap(
                            pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                            pair -> pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : ""
                    ));

            if ("teacon2026".equals(uri.getScheme()) && "/build/".equals(uri.getPath())) {
                savePresharedChunks(
                        Path.of(query.get("path")),
                        new LongIterators.AbstractIndexBasedIterator(0, 0) {
                            private static final int W = 230;

                            @Override
                            protected long get(int location) {
                                return ChunkPos.pack(location % W - W / 2, location / W - W / 2);
                            }

                            @Override
                            protected void remove(int location) {
                                throw new UnsupportedOperationException();
                            }

                            @Override
                            protected int getMaxPos() {
                                return W * W;
                            }
                        }
                ).whenComplete((_, throwable) -> {
                    if (throwable != null) {
                        LOGGER.warn("Cannot create Preshared Chunk Bundle for teacon-2026", throwable);
                    }

                    MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                    if (server != null) {
                        server.halt(false);
                    }
                });
            } else {
                throw new UnsupportedOperationException("Unsupported preset: " + preset);
            }
        }
    }
}
