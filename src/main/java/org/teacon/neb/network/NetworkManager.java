package org.teacon.neb.network;

import com.google.common.collect.ImmutableSet;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.network.connection.ConnectionUtils;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.teacon.neb.NEBConfigs;
import org.teacon.neb.network.aggregate.AggregateBuffer;
import org.teacon.neb.network.aggregate.CompressedPacket;
import org.teacon.neb.network.indexed.IndexLookup;
import org.teacon.neb.network.indexed.IndexPacket;
import org.teacon.neb.utils.ConfigAccess;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.function.BooleanSupplier;

@EventBusSubscriber
public final class NetworkManager {
    private NetworkManager() {
    }

    public static volatile Set<PacketType<?>> USER_BLACK_LIST = ImmutableSet.of(); // Copy-On-Write

    public static void enable(Connection connection) {
        AggregateBuffer.initialize(connection);
    }

    public static void tick(Connection connection) {
        AggregateBuffer buffer = AggregateBuffer.get(connection);
        if (buffer != null) {
            buffer.flush();
        }
    }

    public static void release(Connection connection) {
        AggregateBuffer.release(connection);
    }

    @SuppressWarnings("ConstantValue")
    public static int getMaxFrameVarintSize(ChannelHandlerContext ctx) {
        Connection conn = ConnectionUtils.getConnection(ctx); // null if the connection is non-minecraft ones.
        if (conn == null) {
            return 3;
        }
        ModConfigSpec.ConfigValue<Integer> v =  switch (conn.getSending()) {
            case CLIENTBOUND -> NEBConfigs.PACKET_MAX_VARINT_SIZE_S2C;
            case SERVERBOUND -> NEBConfigs.PACKET_MAX_VARINT_SIZE_C2S;
        };
        return ConfigAccess.getOrDefault(v, 3);
    }

    private static final BooleanSupplier CLIENT_PAUSE;

    static {
        if (FMLEnvironment.getDist().isDedicatedServer()) {
            CLIENT_PAUSE = () -> false;
        } else {
            // noinspection Convert2Lambda, RedundantCast : prevent LinkageError on dedicated server.
            CLIENT_PAUSE = (BooleanSupplier) new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                    return Minecraft.getInstance().isPaused();
                }
            };
        }
    }

    private static boolean isPaused(Connection connection) {
        return switch (connection.getSending()) {
            case SERVERBOUND -> CLIENT_PAUSE.getAsBoolean();
            case CLIENTBOUND -> {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                yield server != null && server.isPaused();
            }
        };
    }

    @Nullable
    public static Packet<?> onSendPacket(Connection connection, Packet<?> packet, ChannelFutureListener sendListener) {
        AggregateBuffer buffer = AggregateBuffer.get(connection);
        if (buffer == null) {
            return unwrapPacket(packet);
        } else if (isPaused(connection) || USER_BLACK_LIST.contains(unwrapType(packet))) {
            buffer.flush();
            return unwrapPacket(packet);
        } else {
            enqueuePacket(connection, packet, buffer);
            if (sendListener != null) {
                buffer.push(sendListener);
            }
            return null;
        }
    }

    public static Packet<?> unwrapPacket(Packet<?> packet) {
        if (packet instanceof TypedPacket<?>(Packet<?> inner, _)) {
            return inner;
        }
        return packet;
    }

    public static PacketType<?> unwrapType(Packet<?> packet) {
        return switch (packet) {
            case TypedPacket<?>(Packet<?> inner, _) -> unwrapType(inner);
            case VanillaCustomPayload payload -> new PacketType<>(packet.type().flow(), payload.payload().type().id());
            default -> packet.type();
        };
    }

    private static void enqueuePacket(Connection connection, Packet<?> packet, AggregateBuffer buffer) {
        switch (packet) {
            case CompressedPacket ignored ->
                    throw new AssertionError("CompressedPacket should NOT be pushed into the packet flow.");
            case VanillaCustomPayload pp -> {
                CustomPacketPayload payload = pp.payload();
                Identifier type = payload.type().id();

                if (!USER_BLACK_LIST.contains(new PacketType<>(connection.getSending(), type)) && IndexLookup.getInstance().getIndex(type) != IndexLookup.EMPTY_INT) {
                    packet = new IndexPacket(switch (connection.getSending()) {
                        case CLIENTBOUND -> IndexPacket.C_TYPE;
                        case SERVERBOUND -> IndexPacket.S_TYPE;
                    }, payload);
                }

                buffer.push(packet);
            }
            case BundlePacket<?> bundle -> {
                for (Packet<?> sub : bundle.subPackets()) {
                    enqueuePacket(connection, sub, buffer);
                }
            }
            case TypedPacket<?>(Packet<?> inner, String type) -> {
                if (inner instanceof BundlePacket<?> bundle) {
                    for (Packet<?> sub : bundle.subPackets()) {
                        enqueuePacket(connection, new TypedPacket<>(sub, type), buffer);
                    }
                } else {
                    buffer.push(packet);
                }
            }
            default -> buffer.push(packet);
        }
    }
}
