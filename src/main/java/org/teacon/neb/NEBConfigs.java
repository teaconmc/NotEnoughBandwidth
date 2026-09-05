package org.teacon.neb;

import com.google.common.collect.ImmutableSet;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import org.teacon.neb.network.NetworkManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@SuppressWarnings("NotNullFieldNotInitialized")
@EventBusSubscriber
public final class NEBConfigs {
    public static ModConfigSpec.ConfigValue<Integer> COMPRESS_WINDOW_SIZE_LOG;

    public static ModConfigSpec.ConfigValue<Boolean> CHUNK_CACHE_EAGERLY;
    public static ModConfigSpec.ConfigValue<Integer> CHUNK_CACHE_BUFFER_SIZE;
    public static ModConfigSpec.ConfigValue<Integer> CHUNK_CACHE_DISTANCE;
    public static ModConfigSpec.ConfigValue<Integer> CHUNK_CACHE_TIMEOUT;

    public static ModConfigSpec.ConfigValue<String> PRESHARED_CHUNK_INSTANCE;
    public static ModConfigSpec.ConfigValue<String> PRESHARED_CHUNK_DYNAMIC_DISPATCH_URL;
    public static ModConfigSpec.ConfigValue<Integer> PRESHARED_CHUNK_COMPRESS_LEVEL;
    public static ModConfigSpec.ConfigValue<Integer> PRESHARED_CHUNK_CACHE_L1_MAX_SERVER;
    public static ModConfigSpec.ConfigValue<Integer> PRESHARED_CHUNK_CACHE_L1_MAX_CLIENT;
    public static ModConfigSpec.ConfigValue<Integer> PRESHARED_CHUNK_REQUEST_TIMEOUT;
    public static ModConfigSpec.ConfigValue<Integer> PRESHARED_CHUNK_FAILURE_COOLDOWN;

    public static ModConfigSpec.ConfigValue<Integer> PACKET_MAX_VARINT_SIZE_C2S;
    public static ModConfigSpec.ConfigValue<Integer> PACKET_MAX_VARINT_SIZE_S2C;
    private static ModConfigSpec.ConfigValue<List<? extends String>> PACKET_BLACKLIST;

    private static List<IConfigSpec> CONFIG_SPECS;

    @SubscribeEvent
    private static void on(FMLConstructModEvent event) {
        ModConfigSpec.Builder server = new ModConfigSpec.Builder();
        ModConfigSpec.Builder client = new ModConfigSpec.Builder();

        COMPRESS_WINDOW_SIZE_LOG = server
                .comment(formatComments("""
                        The base-2 logarithm of the compression window size. See: https://www.jefftk.com/p/zstd-window-size
                        """))
                .defineInRange("zstd.window_log", 20, 19, 27);

        server.comment(formatComments("""
                        Chunk cache is used to temporarily retain chunks that have recently left the major view,
                        in order to reduce frequent enter/leave churn when the player moves near view boundaries.
                        A cached chunk will be evicted if any of the following conditions is met:
                        - It has not been within the major view boundary for a configured amount of time (timeout).
                        - It is farther than the allowed cache distance from the current view center.
                        - The cache exceeds the configured size limit, in which case the oldest cached chunks are removed first.
                        """))
                .push("chunk_cache");
        CHUNK_CACHE_EAGERLY = server
                .comment(formatComments("""
                        [WARNING: May impact performance]
                        Whether cached chunks should stay active if others doesn't load it.
                        If false, chunks will be unloaded if others doesn't load it and sync to client
                        next time when it becomes visible.
                        """))
                .define("aggressively", true);
        CHUNK_CACHE_BUFFER_SIZE = server
                .comment(formatComments("The maximum capacity of the cache queue for recently visited chunks."))
                .defineInRange("buffer_size", 60, 0, Integer.MAX_VALUE);
        CHUNK_CACHE_DISTANCE = server
                .comment(formatComments("""
                        The distance threshold in chunks.
                        If the distance between a cached chunk and the player exceeds
                        this value plus the view distance, the chunk will be forgotten.
                        """))
                .defineInRange("distance", 5, 0, Integer.MAX_VALUE);
        CHUNK_CACHE_TIMEOUT = server
                .comment(formatComments("""
                        The time (in seconds) since the client's last visit.
                        If this timeout is exceeded, the cached chunks will be forgotten.
                        """))
                .defineInRange("timeout", 60, 0, Integer.MAX_VALUE);
        server.pop();

        server.comment(formatComments("""
                        Dispatch chunks in third-party approaches.
                        When syncing chunks, the server sends a small diff instead of full data, reducing network bandwidth usage.
                        """))
                .push("chunk_cdn");
        PRESHARED_CHUNK_INSTANCE = server.comment(formatComments("""
                        The currently active preshared chunk version.
                        """))
                .define("version", "");
        PRESHARED_CHUNK_DYNAMIC_DISPATCH_URL = server.comment(formatComments("""
                        Loads chunks from remove server, accepting http (DONOT USE THIS IN PRODUCTION SERVER!) and https protocol,
                        with Java String#format styled placeholders for %1$s (version), %2$d (gridX), %3$d (gridZ).
                        Remote server must return this correct data, as there won't be much validation in client sides.
                        Leave it to empty to disable this feature.
                        """))
                .define("dynamic_dispatch_url", "");
        PRESHARED_CHUNK_COMPRESS_LEVEL = server
                .comment(formatComments("ZSTD compression level for the Preshared Chunk Bundle."))
                .defineInRange("compress_level", 22, 0, Integer.MAX_VALUE);
        PRESHARED_CHUNK_CACHE_L1_MAX_SERVER = server
                .comment(formatComments("Cache L1's max size"))
                .define("cache_l1_max", 2048);
        PRESHARED_CHUNK_REQUEST_TIMEOUT = server
                .comment(formatComments("Seconds to wait before fetching preshared chunks from dynamic_dispatch_url"))
                .define("request_timeout", 10);
        PRESHARED_CHUNK_FAILURE_COOLDOWN = server
                .comment(formatComments("""
                        After a request fails, subsequent requests will be considered failed
                        without making another request for this amount of time (in seconds).
                        """))
                .define("failure_cooldown", 30);
        server.pop();

        client.comment(formatComments("""
                        Dispatch chunks in third-party approaches.
                        When syncing chunks, the server sends a small diff instead of full data, reducing network bandwidth usage.
                        """))
                .push("chunk_cdn");
        PRESHARED_CHUNK_CACHE_L1_MAX_CLIENT = client
                .comment(formatComments("Cache L1's max size"))
                .define("cache_l1_max", 256);
        client.pop();

        server.push("packet");
        server.comment(formatComments("""
                        The maximum number of bytes allowed for the VarInt-encoded packet length field.
                        Larger values permit larger packets, but may increase memory usage and expose the
                        server to out-of-memory (OOM) attacks if untrusted clients send oversized frames.
                        """))
                .push("size_varint_max");
        PACKET_MAX_VARINT_SIZE_C2S = server.defineInRange("c2s", 3, 1, VarInt.MAX_VARINT_SIZE);
        PACKET_MAX_VARINT_SIZE_S2C = server.defineInRange("s2c", 3, 1, VarInt.MAX_VARINT_SIZE);
        server.pop();
        PACKET_BLACKLIST = server
                .comment(formatComments("""
                        Packets excluded from processing by NotEnoughBandwidth. (For Velocity compatibility, e.g.)
                        Each entry must be in the format "<direction>@<packet_id>", where <direction>
                        is "c2s" or "s2c", and <packet_id> is a Minecraft packet identifier (like minecraft:register).
                        """))
                .defineList(
                        "blacklist", ArrayList::new, () -> "",
                        element -> element instanceof String value && parsePacketDescriptor(value) != null
                );
        server.pop();

        ModConfigSpec serverSpec = server.build(), clientSpec = client.build();
        NotEnoughBandwidth.MOD_CONTAINER.registerConfig(ModConfig.Type.SERVER, serverSpec);
        NotEnoughBandwidth.MOD_CONTAINER.registerConfig(ModConfig.Type.CLIENT, clientSpec);
        CONFIG_SPECS = List.of(serverSpec, clientSpec);
    }

    @EventBusSubscriber(Dist.CLIENT)
    private static class Client {
        @SubscribeEvent
        private static void on(FMLConstructModEvent event) {
            NotEnoughBandwidth.MOD_CONTAINER.registerExtensionPoint(
                    IConfigScreenFactory.class, ConfigurationScreen::new
            );
        }
    }

    private static String[] formatComments(String comments) {
        return comments.lines().map(s -> " " + s).toArray(String[]::new);
    }

    @SubscribeEvent
    private static void on(ModConfigEvent.Loading event) {
        updateConfig(event.getConfig().getSpec());
    }

    @SubscribeEvent
    private static void on(ModConfigEvent.Reloading event) {
        updateConfig(event.getConfig().getSpec());
    }

    private static void updateConfig(IConfigSpec spec) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (!CONFIG_SPECS.contains(spec) || server == null) {
            return;
        }

        NetworkManager.USER_BLACK_LIST = ImmutableSet.copyOf(
                PACKET_BLACKLIST.get().stream()
                        .map(NEBConfigs::parsePacketDescriptor)
                        .filter(Objects::nonNull)
                        .iterator()
        );

        PlayerList playerList = server.getPlayerList();
        playerList.setViewDistance(playerList.getViewDistance() + CHUNK_CACHE_DISTANCE.get());
    }

    @Nullable
    private static PacketType<?> parsePacketDescriptor(String value) {
        int i = value.indexOf('@');
        if (i != -1) {
            String bound = value.substring(0, i);
            Identifier location = Identifier.tryParse(value.substring(i + 1));
            if (location != null) {
                return switch (bound) {
                    case "c2s" -> new PacketType<>(PacketFlow.SERVERBOUND, location);
                    case "s2c" -> new PacketType<>(PacketFlow.CLIENTBOUND, location);
                    default -> null;
                };
            }
        }
        return null;
    }
}
