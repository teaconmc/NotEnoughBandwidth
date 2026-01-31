package org.teacon.neb.network.indexed;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.registration.PayloadRegistration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class IndexLookup {
    public static final int EMPTY_INT = -2;

    private static final IndexLookup EMPTY_LOOKUP = new IndexLookup(List.of());
    private static final AtomicReference<IndexLookup> INSTANCE = new AtomicReference<>();

    public static IndexLookup getInstance() {
        IndexLookup instance = INSTANCE.getPlain();
        if (instance == null) {
            instance = INSTANCE.get();
        }

        return Objects.requireNonNullElse(instance, EMPTY_LOOKUP);
    }

    @SuppressWarnings({"UnstableApiUsage"})
    public static void initialize(Map<Identifier, PayloadRegistration<?>> payloads) {
        List<Identifier> packets = new ArrayList<>();
        for (PayloadRegistration<?> registration : payloads.values()) {
            if (registration.optional()) { // TODO: How to deal with optional packets?
                continue;
            }

            packets.add(registration.id());
        }

        INSTANCE.set(new IndexLookup(packets));
    }

    private final Object2IntMap<Identifier> location2id;

    private final Int2ObjectMap<Identifier> id2location;

    private IndexLookup(List<Identifier> locations) {
        location2id = new Object2IntOpenHashMap<>(locations.size());
        location2id.defaultReturnValue(EMPTY_INT);
        id2location = new Int2ObjectOpenHashMap<>(locations.size());

        if (locations.isEmpty()) {
            return;
        }

        locations.sort(null);
        for (int i = 0; i < locations.size(); i++) {
            Identifier location = locations.get(i);
            if (location2id.put(location, i) != EMPTY_INT || id2location.put(i, location) != null) {
                throw new RuntimeException("Duplicate packet registration: " + location);
            }
        }
    }

    public int getIndex(Identifier type) {
        return location2id.getInt(type);
    }

    public Identifier getType(int id) {
        return id2location.get(id);
    }
}
