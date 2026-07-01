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
import net.neoforged.neoforge.network.payload.SyncAttachmentsPayload;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.teacon.neb.network.TypedPacket;
import org.teacon.neb.network.VanillaCustomPayload;
import org.teacon.neb.network.indexed.IndexPacket;

import java.util.Iterator;

@NullMarked
public final class Snapshot implements Iterable<Snapshot.Entry> {
    private Snapshot() {
    }

    private static final ThreadLocal<Snapshot> SNAPSHOT = ThreadLocal.withInitial(Snapshot::new);

    private enum State {
        FREE, BUILDING, PUBLISHED
    }

    private State state = State.FREE;

    @Nullable
    private SnapshotContext context;
    private int totalSize = 0, compressedSize = 0;
    private final Object2LongMap<String> packets = new Object2LongOpenHashMap<>();

    private static long packSizeRatio(int size, float ratio) {
        return ((long) Float.floatToIntBits(ratio) << 32) | (size & 0xFFFFFFFFL);
    }

    private static int unpackSize(long value) {
        return (int) (value & 0xFFFFFFFFL);
    }

    private static float unpackRatio(long value) {
        return Float.intBitsToFloat((int) (value >> 32));
    }

    public static Snapshot prepare(SnapshotContext context) {
        Snapshot instance = SNAPSHOT.get();
        if (instance.state != State.FREE) {
            throw new IllegalStateException("Not FREE: " + instance.state);
        }

        instance.state = State.BUILDING;
        instance.context = context;
        return instance;
    }

    public void put(Packet<?> packet, int size) {
        if (this.state != State.BUILDING) {
            throw new IllegalStateException("Not BUILDING: " + this.state);
        }

        String type = crackType(packet);
        packets.put(type, packSizeRatio(unpackSize(packets.getOrDefault(type, 0)) + size, Float.NaN));
    }

    @SuppressWarnings("UnstableApiUsage")
    private String crackType(Packet<?> packet) {
        final String packetID = packet.type().id().toString();

        return switch (packet) {
            case VanillaCustomPayload payload -> {
                String payloadID = payload.payload().type().id().toString();
                if (payload.payload() instanceof SyncAttachmentsPayload attachments && attachments.types().size() == 1) {
                    Identifier location = NeoForgeRegistries.ATTACHMENT_TYPES.getKey(attachments.types().getFirst());
                    if (location != null) {
                        yield payloadID + "[type=" + location + "]";
                    }
                }
                yield payloadID;
            }
            case TypedPacket(Packet<?> _, String packetType) -> packetType;
            case IndexPacket(PacketType<IndexPacket> _, CustomPacketPayload payload) -> payload.type().id().toString();
            case ClientboundBlockEntityDataPacket entityData -> {
                Identifier location = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entityData.getType());
                if (location != null) {
                    yield packetID + "[type=" + location + "]";
                }
                yield packetID;
            }
            default -> packetID;
        };
    }

    public void publish(int totalSize, int compressedSize) {
        if (this.state != State.BUILDING) {
            throw new IllegalStateException("Not BUILDING: " + this.state);
        }

        assert this.context != null;
        for (Object2LongMap.Entry<String> entry : Object2LongMaps.fastIterable(packets)) {
            int size = unpackSize(entry.getLongValue());
            float ratio = this.context.computeRatio(entry.getKey(), totalSize, compressedSize, size);
            entry.setValue(packSizeRatio(size, ratio));
        }

        this.state = State.PUBLISHED;
        this.totalSize = totalSize;
        this.compressedSize = compressedSize;
        this.context.publish(this);

        this.state = State.FREE;
        this.context = null;
        this.totalSize = 0;
        this.compressedSize = 0;
        this.packets.clear();
    }

    public int getCompressedSize() {
        return compressedSize;
    }

    public int getTotalSize() {
        return totalSize;
    }

    public static final class Entry {
        private String type = "";
        private int size;
        private float ratio;

        private Entry() {
        }

        public String getType() {
            return type;
        }

        public int getSize() {
            return size;
        }

        public float getRatio() {
            return ratio;
        }
    }

    public Iterator<Entry> iterator() {
        if (this.state != State.PUBLISHED) {
            throw new IllegalStateException("Not PUBLISHED: " + this.state);
        }

        return new Iterator<>() {
            private final Entry entry = new Entry();
            private final ObjectIterator<Object2LongMap.Entry<String>> delegate = Object2LongMaps.fastIterator(packets);

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public Entry next() {
                Object2LongMap.Entry<String> e = delegate.next();
                entry.type = e.getKey();
                entry.size = unpackSize(e.getLongValue());
                entry.ratio = unpackRatio(e.getLongValue());
                return entry;
            }
        };
    }
}
