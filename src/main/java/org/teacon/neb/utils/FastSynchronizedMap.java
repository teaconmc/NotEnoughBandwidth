package org.teacon.neb.utils;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import it.unimi.dsi.fastutil.objects.ObjectSets.SynchronizedSet;

import java.util.function.Consumer;

public class FastSynchronizedMap<V> extends Long2ObjectMaps.SynchronizedMap<V> {
    public FastSynchronizedMap(Long2ObjectMap<V> map) {
        super(map);
    }

    private static final class FastSynchronizedSet<K> extends SynchronizedSet<Entry<K>> implements FastEntrySet<K> {
        FastSynchronizedSet(FastEntrySet<K> map, Object sync) {
            super(map, sync);
        }

        @Override
        public ObjectIterator<Entry<K>> fastIterator() {
            return ((FastEntrySet<K>) collection).fastIterator();
        }

        @Override
        public void fastForEach(Consumer<? super Entry<K>> consumer) {
            ((FastEntrySet<K>) collection).fastForEach(consumer);
        }
    }

    @Override
    public ObjectSet<Entry<V>> long2ObjectEntrySet() {
        synchronized (sync) {
            if (entries == null) {
                ObjectSet<Entry<V>> set = map.long2ObjectEntrySet();
                entries = set instanceof Long2ObjectMap.FastEntrySet<V> fast ? new FastSynchronizedSet<>(fast, sync) : ObjectSets.synchronize(set, sync);
            }
            return entries;
        }
    }
}
