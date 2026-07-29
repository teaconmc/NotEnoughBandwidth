package org.teacon.neb.utils;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.Cleaner;
import java.lang.ref.SoftReference;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.StampedLock;

public final class ScopedArrayAllocator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScopedArrayAllocator.class);

    public ScopedArrayAllocator() {
    }

    public Scope newScope() {
        return new Scope();
    }

    private static final ScopedValue<Scope> SCOPE = ScopedValue.newInstance();
    private final Map<Class<?>, Storage<?>> types = new ConcurrentHashMap<>();

    public static <T> T allocate(Class<T> clazz, int length) {
        return allocateImpl(clazz, length, true);
    }

    public static <T> T allocateUninitialized(Class<T> clazz, int length) {
        return allocateImpl(clazz, length, false);
    }

    private static <T> T allocateImpl(Class<T> clazz, int length, boolean zeroed) {
        if (!clazz.isArray()) {
            throw expectingArray(clazz);
        }

        if (SCOPE.isBound()) {
            return SCOPE.get().allocateScoped(clazz, length, zeroed);
        } else {
            return allocateArray(clazz, length);
        }
    }

    public final class Scope implements AutoCloseable {
        private static final Cleaner CLEANER = Cleaner.create();

        private final Queue<Stamp<?>> allocated = new ConcurrentLinkedQueue<>();

        private record Stamp<T>(T array, Storage<T> storage) {
        }

        private final Cleaner.Cleanable cleanable;

        private Scope() {
            Queue<Stamp<?>> allocated = this.allocated; // DO NOT EDIT: Prevent capturing 'this' in the cleaner!
            cleanable = CLEANER.register(this, () -> releaseSpecific(allocated));
        }

        public void run(Runnable runnable) {
            ScopedValue.where(SCOPE, this).run(runnable);
        }

        public <T, X extends Throwable> T call(ScopedValue.CallableOp<T, X> callable) throws X {
            return ScopedValue.where(SCOPE, this).call(callable);
        }

        @SuppressWarnings("unchecked")
        private <T> T allocateScoped(Class<T> clazz, int length, boolean zeroed) {
            Storage<T> storage = (Storage<T>) types.computeIfAbsent(clazz, Storage::new);
            T array = storage.allocate(length, zeroed);
            allocated.add(new Stamp<>(array, storage));
            return array;
        }

        private static final ScopedValue<Boolean> IS_MANUAL = ScopedValue.newInstance();

        @Override
        public void close() {
            ScopedValue.where(IS_MANUAL, true).run(cleanable::clean);
        }

        private static void releaseSpecific(Queue<Stamp<?>> pool) {
            if (!IS_MANUAL.isBound()) {
                LOGGER.warn("Congratulations, you've forgot to release a ScopedArrayAllocator.Scope and leaked massive memories!");
            }

            Stamp<?> stamp;
            while ((stamp = pool.poll()) != null) {
                recycleStamp(stamp);
            }
        }

        private static <T> void recycleStamp(Stamp<T> stamp) {
            stamp.storage.recycle(stamp.array);
        }
    }

    private static final class Storage<T /* extends array */> {
        private final Class<T> clazz;
        private final StampedLock lock = new StampedLock();
        private final Int2ObjectOpenHashMap<Queue<SoftReference<T>>> storage = new Int2ObjectOpenHashMap<>();

        private Storage(Class<T> clazz) {
            this.clazz = clazz;
        }

        public T allocate(int length, boolean zeroed) {
            Queue<SoftReference<T>> queue = getQueue(length);
            while (true) {
                SoftReference<T> reference = queue.poll();
                if (reference == null) {
                    return allocateArray(clazz, length);
                }
                T array = reference.get();
                if (array != null) {
                    if (zeroed) {
                        erase(array);
                    }
                    return array;
                }
            }
        }

        public void recycle(T array) {
            getQueue(Array.getLength(array)).add(new SoftReference<>(array));
        }

        private Queue<SoftReference<T>> getQueue(int length) {
            if (ThreadLocalRandom.current().nextFloat() <= 1f / 1000) {
                long writeLock = lock.writeLock();

                ObjectIterator<Int2ObjectMap.Entry<Queue<SoftReference<T>>>> iterator = Int2ObjectMaps.fastIterator(storage);
                while (iterator.hasNext()) {
                    Int2ObjectMap.Entry<Queue<SoftReference<T>>> entry = iterator.next();

                    entry.getValue().removeIf(reference -> reference.refersTo(null));
                    if (entry.getValue().isEmpty()) {
                        iterator.remove();
                    }
                }

                Queue<SoftReference<T>> queue = storage.computeIfAbsent(length, _ -> new ConcurrentLinkedQueue<>());
                storage.trim(storage.size() * 2); // Prevent frequently rehash.
                lock.unlockWrite(writeLock);
                return queue;
            }

            long readLock = lock.readLock();
            Queue<SoftReference<T>> queue = storage.get(length);
            if (queue != null) {
                lock.unlockRead(readLock);
                return queue;
            }

            long writeLock = lock.tryConvertToWriteLock(readLock);
            if (writeLock == 0) {
                lock.unlockRead(readLock);
                writeLock = lock.writeLock();
            }
            queue = storage.computeIfAbsent(length, _ -> new ConcurrentLinkedQueue<>());
            lock.unlockWrite(writeLock);
            return queue;
        }

        private void erase(T array) {
            if (clazz == int[].class) {
                Arrays.fill((int[]) array, 0);
            } else if (clazz == short[].class) {
                Arrays.fill((short[]) array, (short) 0);
            } else if (clazz == long[].class) {
                Arrays.fill((long[]) array, 0L);
            } else if (clazz == byte[].class) {
                Arrays.fill((byte[]) array, (byte) 0);
            } else if (clazz == char[].class) {
                Arrays.fill((char[]) array, '\0');
            } else if (clazz == boolean[].class) {
                Arrays.fill((boolean[]) array, false);
            } else if (clazz == float[].class) {
                Arrays.fill((float[]) array, 0f);
            } else if (clazz == double[].class) {
                Arrays.fill((double[]) array, 0d);
            } else {
                Arrays.fill((Object[]) array, null);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocateArray(Class<T> clazz, int length) {
        return (T) Array.newInstance(clazz.componentType(), length);
    }

    private static IllegalArgumentException expectingArray(Class<?> clazz) {
        return new IllegalArgumentException("Expecting array type: " + clazz);
    }
}
