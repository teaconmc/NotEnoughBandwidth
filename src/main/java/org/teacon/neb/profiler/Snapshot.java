package org.teacon.neb.profiler;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;
import org.teacon.neb.network.VanillaCustomPayload;
import org.teacon.neb.network.indexed.IndexPacket;

public final class Snapshot implements Iterable<Object2LongMap.Entry<String>> {
    private Snapshot() {
    }

    private static final ThreadLocal<Snapshot> SNAPSHOT = ThreadLocal.withInitial(Snapshot::new);

    private int totalSize, compressedSize;
    private final Object2LongMap<String> packets = new Object2LongOpenHashMap<>();

    public static Snapshot prepare() {
        Snapshot instance = SNAPSHOT.get();
        instance.totalSize = 0;
        instance.compressedSize = 0;
        instance.packets.clear();

        return instance;
    }

    public void put(Packet<?> packet, int size) {
        final Identifier packetID = packet.type().id();

        String type = switch (packet) {
            case VanillaCustomPayload payload -> payload.payload().type().id().toString();

            case IndexPacket(PacketType<@NotNull IndexPacket> ignored, CustomPacketPayload payload) ->
                    payload.type().id().toString();

            case ClientboundBlockEntityDataPacket entityData -> {
                Identifier location = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entityData.getType());
                if (location != null) {
                    yield packetID + "#" + location;
                } else {
                    yield packetID.toString();
                }
            }

            default -> packetID.toString();
        };

        packets.put(type, Math.toIntExact(packets.getOrDefault(type, 0) + size));
    }

    public Snapshot build(int totalSize, int compressedSize) {
        if (this.totalSize != 0 || this.compressedSize != 0) {
            throw new IllegalStateException("Metadata has already been set.");
        }
        this.totalSize = totalSize;
        this.compressedSize = compressedSize;

        return this;
    }

    @NotNull
    public ObjectIterator<Object2LongMap.Entry<String>> iterator() {
        return Object2LongMaps.fastIterable(packets).iterator();
    }

    public int getCompressedSize() {
        return compressedSize;
    }

    public int getTotalSize() {
        return totalSize;
    }

    public static String getType(Object2LongMap.Entry<String> entry) {
        return entry.getKey();
    }

    public static int getSize(Object2LongMap.Entry<String> entry) {
        return (int) (entry.getLongValue() & 0xFFFFFFFFL);
    }

    public static float getCompressibility(Object2LongMap.Entry<String> entry) {
        return Float.intBitsToFloat((int) ((entry.getLongValue() >>> 32) & 0xFFFFFFFFL));
    }

    public static long withCompressibility(Object2LongMap.Entry<String> entry, float compressibility) {
        return (((long) Float.floatToIntBits(compressibility)) << 32) | getSize(entry);
    }
}
