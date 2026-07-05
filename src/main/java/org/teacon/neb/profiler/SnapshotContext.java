package org.teacon.neb.profiler;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class SnapshotContext {
    private final Consumer<Snapshot> consumer;

    private final Map<String, float[]> compressibility = new ConcurrentHashMap<>();

    public SnapshotContext(Consumer<Snapshot> consumer) {
        this.consumer = consumer;
    }

    public float computeRatio(String type, int totalSize, int compressedSize, int size) {
        float compressibility = Math.clamp(compressedSize / (float) totalSize, 0, 1);
        float weight = size / (float) totalSize;

        float[] ratio = new float[1];
        this.compressibility.compute(type, (_, samples) -> {
            if (samples == null) {
                samples = PacketCompressibility.makeEmpty();
            }
            ratio[0] = PacketCompressibility.putSample(samples, compressibility, weight);
            return samples;
        });

        return ratio[0];
    }

    public void publish(Snapshot snapshot) {
        consumer.accept(snapshot);
    }

    private static final class PacketCompressibility {
        private static final int SAMPLE = Integer.parseInt(Objects.requireNonNullElse(System.getenv("NEB_PROFILER_COMPRESSIBILITY_SAMPLE"), "50"));

        public static float[] makeEmpty() {
            return new float[SAMPLE * 2 + 3];
        }

        private static final int I_T_INDEX = SAMPLE * 2, I_T_VALUE = I_T_INDEX + 1, I_T_WEIGHT = I_T_VALUE + 1;

        public static float putSample(float[] samples, float value, float weight) {
            int index = Float.floatToRawIntBits(samples[I_T_INDEX]);
            int valueI = index << 1, weightI = valueI | 1;

            float replacedSampleValue = samples[valueI];
            float replacedSampleWeight = samples[weightI];
            samples[valueI] = value;
            samples[weightI] = weight;

            samples[I_T_INDEX] = Float.intBitsToFloat((index + 1) % SAMPLE);
            samples[I_T_VALUE] += value * weight - replacedSampleValue * replacedSampleWeight;
            samples[I_T_WEIGHT] += weight - replacedSampleWeight;

            return samples[I_T_VALUE] / samples[I_T_WEIGHT];
        }
    }
}
