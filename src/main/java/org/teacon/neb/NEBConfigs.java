package org.teacon.neb;

import com.google.common.collect.ImmutableSet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.teacon.neb.network.NetworkManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@EventBusSubscriber(modid = NotEnoughBandwidth.MODID)
public final class NEBConfigs {
    private static ModConfigSpec CONFIG_SPEC;
    public static ModConfigSpec.ConfigValue<@NotNull Integer> COMPRESS_WINDOW_SIZE_LOG;

    public static ModConfigSpec.ConfigValue<@NotNull Integer> CHUNK_CACHE_BUFFER_SIZE;
    public static ModConfigSpec.ConfigValue<@NotNull Integer> CHUNK_CACHE_DISTANCE;
    public static ModConfigSpec.ConfigValue<@NotNull Integer> CHUNK_CACHE_TIMEOUT;

    private static ModConfigSpec.ConfigValue<@NotNull List<? extends String>> PACKET_BLACKLIST;

    @SubscribeEvent
    private static void on(FMLConstructModEvent event) {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        COMPRESS_WINDOW_SIZE_LOG = builder
                .comment(" The base-2 logarithm of the compression window size. See: https://www.jefftk.com/p/zstd-window-size")
                .defineInRange("zstd.window_log", 20, 19, 27);

        builder.comment("""
                 Chunk cache is used to temporarily retain chunks that have recently left the major view,
                 in order to reduce frequent enter/leave churn when the player moves near view boundaries.
                 A cached chunk will be evicted if any of the following conditions is met:
                 - It has not been within the major view boundary for a configured amount of time (timeout).
                 - It is farther than the allowed cache distance from the current view center.
                 - The cache exceeds the configured size limit, in which case the oldest cached chunks are removed first.
                """.split("\n")
        ).push("chunk_cache");
        CHUNK_CACHE_BUFFER_SIZE = builder
                .comment(" The maximum capacity of the cache queue for recently visited chunks.")
                .defineInRange("buffer_size", 60, 0, Integer.MAX_VALUE);
        CHUNK_CACHE_DISTANCE = builder
                .comment(" The distance threshold in chunks.")
                .comment(" If the distance between a cached chunk and the player exceeds")
                .comment(" this value plus the view distance, the chunk will be forgotten.")
                .defineInRange("distance", 5, 0, Integer.MAX_VALUE);
        CHUNK_CACHE_TIMEOUT = builder
                .comment(" The time (in seconds) since the client's last visit.")
                .comment(" If this timeout is exceeded, the cached chunks will be forgotten.")
                .defineInRange("timeout", 60, 0, Integer.MAX_VALUE);
        builder.pop();

        PACKET_BLACKLIST = builder.defineList(
                "packet_blacklist", ArrayList::new, () -> "",
                element -> element instanceof String value && parsePacketDescriptor(value) != null
        );
        CONFIG_SPEC = builder.build();

        NotEnoughBandwidth.MOD_CONTAINER.registerConfig(ModConfig.Type.SERVER, CONFIG_SPEC);
        NotEnoughBandwidth.MOD_CONTAINER.registerExtensionPoint(
                IConfigScreenFactory.class, ConfigurationScreen::new
        );
    }

    @SubscribeEvent
    private static void on(ModConfigEvent.Loading event) {
        updateUserBlacklist(event.getConfig().getSpec());
    }

    @SubscribeEvent
    private static void on(ModConfigEvent.Reloading event) {
        updateUserBlacklist(event.getConfig().getSpec());
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

    /// Set the following user black list to make NEB compatible with Velocity.
    /// - s2c@minecraft:login
    /// - c2s@minecraft:keep_alive
    /// - s2c@minecraft:keep_alive
    /// - s2c@minecraft:command_suggestions
    /// - s2c@minecraft:commands
    /// - c2s@minecraft:chat_command
    /// - c2s@minecraft:client_command
    /// - c2s@minecraft:command_suggestion
    /// - s2c@minecraft:player_info_remove
    /// - s2c@minecraft:player_info_update
    private static void updateUserBlacklist(IConfigSpec spec) {
        if (spec == CONFIG_SPEC) {
            NetworkManager.USER_BLACK_LIST = ImmutableSet.copyOf(
                    PACKET_BLACKLIST.get().stream()
                            .map(NEBConfigs::parsePacketDescriptor)
                            .filter(Objects::nonNull)
                            .iterator()
            );
        }
    }
}
