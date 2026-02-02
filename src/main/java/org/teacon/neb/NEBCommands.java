package org.teacon.neb;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Util;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.teacon.neb.network.chunk.preshare.providers.PresharedChunkServer;
import org.teacon.neb.profiler.ProfilerChannel;
import org.teacon.neb.profiler.impl.SimpleProfiler;
import org.teacon.neb.utils.vm.LookupAccess;

import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@EventBusSubscriber(modid = NotEnoughBandwidth.MODID)
public class NEBCommands {
    private NEBCommands() {
    }

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final class PlayerCommandSourceAccessor {
        private PlayerCommandSourceAccessor() {
        }

        private static final Class<?> CLAZZ;
        private static final VarHandle SERVER_PLAYER;

        static {
            List<Class<?>> candidates = new ArrayList<>(1);
            for (int i = 1; true; i++) {
                try {
                    candidates.add(Class.forName(ServerPlayer.class.getName() + "$" + i));
                } catch (ClassNotFoundException e) {
                    break;
                }
            }

            List<Field> list = candidates.stream()
                    .filter(CommandSource.class::isAssignableFrom)
                    .flatMap(clazz -> Arrays.stream(clazz.getDeclaredFields()))
                    .filter(field -> field.getType() == ServerPlayer.class)
                    .toList();
            if (list.size() != 1) {
                throw new AssertionError("Cannot locate ServerPlayer$3 from candidates: " + list);
            }

            Field field = list.getFirst();

            try {
                CLAZZ = field.getDeclaringClass();
                SERVER_PLAYER = LookupAccess.IMPL_LOOKUP.findVarHandle(CLAZZ, field.getName(), ServerPlayer.class)
                        .withInvokeBehavior();
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        @Nullable
        public static ServerPlayer from(CommandSource source) {
            return CLAZZ.isInstance(source) ? (ServerPlayer) SERVER_PLAYER.get(source) : null;
        }
    }

    private static final PermissionNode<@NotNull Boolean> ADMIN_PERMISSION = new PermissionNode<>(
            NotEnoughBandwidth.id("command.admin"), PermissionTypes.BOOLEAN,
            (player, uuid, context) -> {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (player == null || server == null) {
                    return false;
                }
                NameAndId id = player.nameAndId();
                return server.isSingleplayerOwner(id) || server.getProfilePermissions(id).level() == PermissionLevel.OWNERS;
            }
    );

    private static boolean checkAdministrator(CommandSourceStack source) {
        if (source.source instanceof MinecraftServer) {
            return true;
        } else {
            ServerPlayer player = PlayerCommandSourceAccessor.from(source.source);
            return player != null && PermissionAPI.getPermission(player, ADMIN_PERMISSION);
        }
    }

    @SubscribeEvent
    public static void on(PermissionGatherEvent.Nodes event) {
        event.addNodes(ADMIN_PERMISSION);
    }

    @SubscribeEvent
    private static void on(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("neb")
                        .then(Commands.literal("preshared")
                                .requires(source -> {
                                    if (source.source instanceof MinecraftServer) {
                                        return true;
                                    }
                                    if (!FMLEnvironment.isProduction()) {
                                        ServerPlayer player = PlayerCommandSourceAccessor.from(source.source);
                                        if (player != null) {
                                            return source.getServer().isSingleplayerOwner(player.nameAndId());
                                        }
                                    }
                                    return false;
                                })
                                .then(Commands.literal("create")
                                        .then(Commands.argument("path", StringArgumentType.greedyString())
                                                .executes(context -> {
                                                    MinecraftServer server = context.getSource().getServer();

                                                    PresharedChunkServer.create(server, Path.of(context.getArgument("path", String.class)))
                                                            .whenCompleteAsync((_, exception) -> {
                                                                if (exception == null) {
                                                                    context.getSource().sendSystemMessage(Component.translatable("neb.preshared.create.success"));
                                                                } else {
                                                                    context.getSource().sendSystemMessage(Component.translatable("neb.preshared.create.failed"));
                                                                }
                                                            }, server);

                                                    return Command.SINGLE_SUCCESS;
                                                })
                                        )
                                )
                                .then(Commands.literal("reload").executes(context -> onReloadPresharedChunks(context, true)))
                                .then(Commands.literal("unload").executes(context -> onReloadPresharedChunks(context, false)))
                        )
                        .then(Commands.literal("profiler")
                                .requires(NEBCommands::checkAdministrator)
                                .then(Commands.literal("start").executes(context -> {
                                    sendResult(context, ProfilerChannel.SERVER.add(new SimpleProfiler()));
                                    context.getSource().sendSystemMessage(Component.translatable("neb.profiler.server.start"));
                                    return Command.SINGLE_SUCCESS;
                                }))
                                .then(Commands.literal("stop").executes(context -> {
                                    sendResult(context, ProfilerChannel.SERVER.take(SimpleProfiler.class));
                                    return Command.SINGLE_SUCCESS;
                                }))
                        )
        );
    }

    private static int onReloadPresharedChunks(CommandContext<CommandSourceStack> context, boolean load) {
        MinecraftServer server = context.getSource().getServer();

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (int i = players.size() - 1; i >= 0; i--) {
            players.get(i).connection.disconnect(Component.translatable("neb.preshared.create.connect_again"));
        }

        try {
            if (load) {
                PresharedChunkServer.load(server);
            } else {
                PresharedChunkServer.unload();
            }
        } catch (IOException e) {
            LOGGER.warn("Cannot load preshared-chunk data.", e);
            return -2;
        }

        server.sendSystemMessage(Component.translatable("neb.preshared.create.connect_again"));
        return Command.SINGLE_SUCCESS;
    }

    private record SimpleProfileResult(String body) implements CustomPacketPayload {
        private static final Type<@NotNull SimpleProfileResult> TYPE = new Type<>(NotEnoughBandwidth.id("s2c/simple_profile_result"));

        @Override
        @NotNull
        public Type<? extends @NotNull CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    private static void on(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NotEnoughBandwidth.MOD_CONTAINER.getModInfo().getVersion().toString());
        registrar.playToClient(SimpleProfileResult.TYPE, StreamCodec.composite(ByteBufCodecs.STRING_UTF8, SimpleProfileResult::body, SimpleProfileResult::new));
    }

    private static void sendResult(CommandContext<CommandSourceStack> context, @Nullable SimpleProfiler profiler) {
        if (profiler != null) {
            context.getSource().sendSystemMessage(Component.translatable("neb.profiler.server.stop"));

            String result = profiler.build();

            if (context.getSource().source instanceof MinecraftServer) {
                try {
                    Path path = Files.createTempFile("neb-", ".csv");
                    Files.writeString(path, result, StandardCharsets.UTF_8);
                    context.getSource().sendSystemMessage(Component.translatable("neb.profiler.result", path.toString()));
                } catch (IOException e) {
                    LOGGER.warn("Cannot write profile result.", e);
                }
            } else {
                ServerPlayer player = PlayerCommandSourceAccessor.from(context.getSource().source);
                if (player != null) {
                    player.connection.send(new SimpleProfileResult(result));
                }
            }
        }
    }

    @EventBusSubscriber(modid = NotEnoughBandwidth.MODID, value = Dist.CLIENT)
    private static final class ClientCommands {
        private ClientCommands() {
        }

        @SubscribeEvent
        private static void on(RegisterClientCommandsEvent event) {
            event.getDispatcher().register(
                    Commands.literal("neb")
                            .then(Commands.literal("profilerc")
                                    .then(Commands.literal("start").executes(context -> {
                                        saveResult(ProfilerChannel.CLIENT.add(new SimpleProfiler()));
                                        Minecraft.getInstance().getChatListener().handleSystemMessage(Component.translatable("neb.profiler.client.start"), false);
                                        return Command.SINGLE_SUCCESS;
                                    }))
                                    .then(Commands.literal("stop").executes(context -> {
                                        saveResult(ProfilerChannel.CLIENT.take(SimpleProfiler.class));
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            )
            );
        }

        @SubscribeEvent
        private static void on(RegisterClientPayloadHandlersEvent event) {
            event.register(SimpleProfileResult.TYPE, (payload, context) -> saveResult(payload.body()));
        }

        private static void saveResult(@Nullable SimpleProfiler profiler) {
            if (profiler != null) {
                Minecraft.getInstance().getChatListener().handleSystemMessage(Component.translatable("neb.profiler.client.stop"), false);

                saveResult(profiler.build());
            }
        }

        public static void saveResult(@NotNull String result) {
            Thread.startVirtualThread(() -> {
                try {
                    Path path = Files.createTempFile("neb-", ".csv");
                    Files.writeString(path, result, StandardCharsets.UTF_8);
                    Minecraft.getInstance().execute(() -> {
                        Util.getPlatform().openPath(path);
                        Minecraft.getInstance().getChatListener().handleSystemMessage(Component.translatable("neb.profiler.result", path.toString()), false);
                    });
                } catch (IOException e) {
                    LOGGER.warn("Cannot write profile result.", e);
                }
            });
        }
    }
}
