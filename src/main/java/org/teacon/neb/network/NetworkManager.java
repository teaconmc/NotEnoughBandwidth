package org.teacon.neb.network;

import com.google.common.collect.ImmutableSet;
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
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.teacon.neb.network.aggregate.AggregateBuffer;
import org.teacon.neb.network.aggregate.CompressedPacket;
import org.teacon.neb.network.indexed.IndexLookup;
import org.teacon.neb.network.indexed.IndexPacket;

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

    public static boolean onSendPacket(Connection connection, Packet<?> packet) {
        AggregateBuffer buffer = AggregateBuffer.get(connection);
        if (buffer == null || USER_BLACK_LIST.contains(packet.type())) {
            return false;
        }

        boolean paused = switch (connection.getSending()) {
            case SERVERBOUND -> CLIENT_PAUSE.getAsBoolean();
            case CLIENTBOUND -> {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                yield server != null && server.isPaused();
            }
        };
        if (paused) {
            buffer.flush();
            return false;
        }

        transformPacket(connection, packet, buffer);
        return true;
    }

    private static void transformPacket(Connection connection, Packet<?> packet, AggregateBuffer buffer) {
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
                    transformPacket(connection, sub, buffer);
                }
            }
            default -> buffer.push(packet);
        }
    }
}
