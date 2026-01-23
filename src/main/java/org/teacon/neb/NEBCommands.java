package org.teacon.neb;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Util;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
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
import org.teacon.neb.profiler.ProfilerChannel;
import org.teacon.neb.profiler.impl.SimpleProfiler;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
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

    private static final class PlayerCommandSourceAccessor {
        private PlayerCommandSourceAccessor() {
        }

        private static final Class<?> CLAZZ;
        private static final VarHandle SERVER_PLAYER;

        static {
            List<Class<?>> candidates = new ArrayList<>(1);
            for (int i = 1; true; i ++) {
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
                SERVER_PLAYER = MethodHandles.privateLookupIn(CLAZZ, MethodHandles.lookup())
                        .findVarHandle(CLAZZ, field.getName(), ServerPlayer.class)
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

    private static final PermissionNode<@NotNull Boolean> PROFILER_PERMISSION = new PermissionNode<>(
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

    private static boolean checkServerProfilerPermission(CommandSourceStack source) {
        ServerPlayer player = PlayerCommandSourceAccessor.from(source.source);
        return player != null && PermissionAPI.getPermission(player, PROFILER_PERMISSION);
    }

    @SubscribeEvent
    public static void on(PermissionGatherEvent.Nodes event) {
        event.addNodes(PROFILER_PERMISSION);
    }

    @SubscribeEvent
    private static void on(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("neb")
                        .then(Commands.literal("profiler")
                                .requires(NEBCommands::checkServerProfilerPermission)
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
            ServerPlayer player = PlayerCommandSourceAccessor.from(context.getSource().source);
            if (player != null) {
                player.connection.send(new SimpleProfileResult(profiler.build()));
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
                    NotEnoughBandwidth.LOGGER.warn("Cannot write profile result.", e);
                }
            });
        }
    }
}
