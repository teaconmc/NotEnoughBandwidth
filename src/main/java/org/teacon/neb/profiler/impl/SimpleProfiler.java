package org.teacon.neb.profiler.impl;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.teacon.neb.profiler.ProfilerChannel;
import org.teacon.neb.profiler.Snapshot;

import java.util.Comparator;

public class SimpleProfiler implements ProfilerChannel.IProfiler {
    private static final class Summary {
        public static final long EMPTY = pack(0, 0, Float.NaN);

        private static final int VALUE_MAX = (1 << 28) - 1;

        public static int getTransmit(long value) {
            return (int) ((value >>> 36) & VALUE_MAX);
        }

        public static int getReceive(long value) {
            return (int) ((value >>> 8) & VALUE_MAX);
        }

        public static float getCompressibility(long value) {
            return (value & 0xFF) / 128f;
        }

        public static long withTransmit(long value, int transmit) {
            return pack(transmit, getReceive(value), getCompressibility(value));
        }

        public static long withReceive(long value, int receive) {
            return pack(getTransmit(value), receive, getCompressibility(value));
        }

        public static long withCompressibility(long value, float compressibility) {
            return pack(getTransmit(value), getReceive(value), compressibility);
        }

        public static long pack(int transmit, int receive, float compressibility) {
            int t = Math.clamp(transmit, 0, VALUE_MAX);
            int r = Math.clamp(receive, 0, VALUE_MAX);
            int c = Float.isNaN(compressibility) ? 0 : (int) Math.clamp(compressibility * 128, 1, 255);
            return ((long) t << 36) | ((long) r << 8) | c;
        }
    }

    private final long startTime = System.currentTimeMillis();
    private int transmit, receive, transmitCompressed, receiveCompressed;
    private final Object2LongMap<String> summary = new Object2LongOpenHashMap<>();

    @Override
    public synchronized void onTransmitPacket(Snapshot snapshot) {
        transmit += snapshot.getTotalSize();
        transmitCompressed += snapshot.getCompressedSize();
        for (Object2LongMap.Entry<String> entry : snapshot) {
            long summary = this.summary.getOrDefault(Snapshot.getType(entry), Summary.EMPTY);
            summary = Summary.pack(
                    Summary.getTransmit(summary) + Snapshot.getSize(entry),
                    Summary.getReceive(summary),
                    Snapshot.getCompressibility(entry)
            );
            this.summary.put(Snapshot.getType(entry), summary);
        }
    }

    @Override
    public synchronized void onReceivePacket(Snapshot snapshot) {
        receive += snapshot.getTotalSize();
        receiveCompressed += snapshot.getCompressedSize();
        for (Object2LongMap.Entry<String> entry : snapshot) {
            long summary = this.summary.getOrDefault(Snapshot.getType(entry), Summary.EMPTY);
            summary = Summary.pack(
                    Summary.getTransmit(summary),
                    Summary.getReceive(summary) + Snapshot.getSize(entry),
                    Snapshot.getCompressibility(entry)
            );
            this.summary.put(Snapshot.getType(entry), summary);
        }
    }

    public synchronized String build() {
        CSVWriter writer = new CSVWriter(new StringBuilder());

        writer.line("### Summary ###")
                .line("Transmit (byte)", transmit)
                .line("Transmit (Compressed) (byte)", transmitCompressed)
                .line("Receive (byte)", receive)
                .line("Receive (Compressed) (byte)", receiveCompressed)
                .line("Total Time (ms)", System.currentTimeMillis() - startTime)
                .line()
                .line("### Packets ###")
                .line("Identifier", "Transmit (byte)", "Receive (byte)", "Compressibility (%)");

        summary.object2LongEntrySet().stream().sorted(Comparator.comparing(Object2LongMap.Entry::getKey)).forEach(entry -> {
            long summary = entry.getLongValue();
            writer.line(entry.getKey(), Summary.getTransmit(summary), Summary.getReceive(summary), Summary.getCompressibility(summary));
        });

        return writer.builder.toString();
    }

    private record CSVWriter(StringBuilder builder) {
        public CSVWriter line(Object... objects) {
            for (int i = 0; i < objects.length; i++) {
                builder.append(objects[i]);
                if (i != objects.length - 1) {
                    builder.append(",");
                }
            }
            builder.append("\r\n");
            return this;
        }
    }
}
