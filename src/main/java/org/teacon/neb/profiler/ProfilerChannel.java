package org.teacon.neb.profiler;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;
import org.teacon.neb.profiler.impl.PrometheusProfiler;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@SuppressWarnings("unchecked")
public final class ProfilerChannel {
    public interface IProfiler {
        void onTransmitPacket(Snapshot snapshot);

        void onReceivePacket(Snapshot snapshot);
    }

    public static final ProfilerChannel CLIENT = new ProfilerChannel();
    public static final ProfilerChannel SERVER = new ProfilerChannel();

    static {
        String port = System.getenv("NEB_PROM_PORT");
        if (port != null) {
            SERVER.add(new PrometheusProfiler(Integer.parseUnsignedInt(port)));
        }
    }

    private final Object2ObjectMap<String, PacketCompressibility> compressibility = new Object2ObjectOpenHashMap<>();

    private volatile IProfiler[] profilers = new IProfiler[0];
    private final Lock READ, WRITE;

    private ProfilerChannel() {
        ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
        READ = rwLock.readLock();
        WRITE = rwLock.writeLock();
    }

    @Nullable
    public <T extends IProfiler> T add(T profiler) {
        Objects.requireNonNull(profiler, "profiler");

        WRITE.lock();
        try {
            IProfiler[] profilers = this.profilers;
            for (int i = 0; i < profilers.length; i++) {
                IProfiler previous = profilers[i];
                if (previous.getClass() == profiler.getClass()) {
                    profilers[i] = profiler;
                    return (T) previous;
                }
            }

            profilers = Arrays.copyOf(profilers, profilers.length + 1);
            profilers[profilers.length - 1] = profiler;
            this.profilers = profilers;
            return null;
        } finally {
            WRITE.unlock();
        }
    }

    @Nullable
    public <T extends IProfiler> T take(Class<T> clazz) {
        Objects.requireNonNull(clazz, "clazz");

        WRITE.lock();
        try {
            IProfiler[] profilers = this.profilers;
            for (int i = 0; i < profilers.length; i++) {
                if (profilers[i].getClass() == clazz) {
                    IProfiler[] profilers2 = new IProfiler[profilers.length - 1];
                    System.arraycopy(profilers, 0, profilers2, 0, i);
                    System.arraycopy(profilers, i + 1, profilers2, i, profilers.length - i - 1);
                    this.profilers = profilers2;
                    return (T) profilers[i];
                }
            }

            return null;
        } finally {
            WRITE.unlock();
        }
    }

    public void onTransmitPacket(Snapshot snapshot) {
        injectCompressibility(snapshot);

        READ.lock();
        try {
            IProfiler[] profilers = this.profilers;
            for (IProfiler profiler : profilers) {
                profiler.onTransmitPacket(snapshot);
            }
        } finally {
            READ.unlock();
        }
    }

    public void onReceivePacket(Snapshot snapshot) {
        injectCompressibility(snapshot);

        READ.lock();
        try {
            IProfiler[] profilers = this.profilers;
            for (IProfiler profiler : profilers) {
                profiler.onReceivePacket(snapshot);
            }
        } finally {
            READ.unlock();
        }
    }

    private synchronized void injectCompressibility(Snapshot snapshot) {
        float compressibility = Math.clamp(snapshot.getCompressedSize() / (float) snapshot.getTotalSize(), 0, 1);
        for (Object2LongMap.Entry<String> entry : snapshot) {
            float v = this.compressibility.computeIfAbsent(Snapshot.getType(entry), rl -> new PacketCompressibility())
                    .putSample(compressibility, Snapshot.getSize(entry) / (float) snapshot.getTotalSize());

            entry.setValue(Snapshot.withCompressibility(entry, v));
        }
    }

    private static final class PacketCompressibility {
        private static final int SAMPLE = Integer.parseInt(Objects.requireNonNullElse(System.getenv("NEB_PROFILER_COMPRESSIBILITY_SAMPLE"), "50"));

        private final float[] samples = new float[SAMPLE * 2]; // value1, weight1, value2, weight2, ...
        private int index = 0;
        private float totalValue = 0, totalWeight = 0;

        // must be synchronized by external locks.
        public float putSample(float value, float weight) {
            int valueI = index << 1, weightI = valueI | 1;

            float replacedSampleValue = samples[valueI];
            float replacedSampleWeight = samples[weightI];
            totalValue -= replacedSampleValue * replacedSampleWeight;
            totalWeight -= replacedSampleWeight;

            index = (index + 1) % SAMPLE;
            samples[valueI] = value;
            samples[weightI] = weight;
            totalValue += value * weight;
            totalWeight += weight;

            return totalValue / totalWeight;
        }
    }
}
