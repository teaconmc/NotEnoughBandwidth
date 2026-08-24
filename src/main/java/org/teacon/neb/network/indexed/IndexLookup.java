package org.teacon.neb.network.indexed;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.registration.PayloadRegistration;

import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class IndexLookup {
    public static final int EMPTY_INT = -2;

    private static IndexLookup INSTANCE = new IndexLookup(List.of());

    public static IndexLookup getInstance() {
        return INSTANCE;
    }

    @SuppressWarnings({"UnstableApiUsage"})
    public static void initialize(Map<Identifier, PayloadRegistration<?>> payloads) {
        List<Identifier> packets = new ArrayList<>();
        for (PayloadRegistration<?> registration : payloads.values()) {
            if (registration.optional()) { // FIXME: How to deal with optional packets?
                continue;
            }

            packets.add(registration.id());
        }

        INSTANCE = new IndexLookup(packets);
        VarHandle.fullFence();
    }

    private final Object2IntMap<Identifier> location2id;

    private final Identifier[] id2location;

    private IndexLookup(List<Identifier> locations) {
        location2id = new Object2IntOpenHashMap<>(locations.size());
        location2id.defaultReturnValue(EMPTY_INT);
        id2location = new Identifier[locations.size()];

        if (locations.isEmpty()) {
            return;
        }

        locations.sort(null);
        for (int i = 0; i < locations.size(); i++) {
            Identifier location = locations.get(i);
            id2location[i] = location;

            if (location2id.put(location, i) != EMPTY_INT) {
                throw new RuntimeException("Duplicate packet registration: " + location);
            }
        }
    }

    public int getIndex(Identifier type) {
        return location2id.getInt(type);
    }

    public Identifier getType(int id) {
        if (id < 0 || id >= id2location.length) {
            throw new IllegalArgumentException("Unknown packet index: " + id);
        }
        return id2location[id];
    }
}
