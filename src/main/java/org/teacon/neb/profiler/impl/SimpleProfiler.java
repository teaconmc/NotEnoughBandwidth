package org.teacon.neb.profiler.impl;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.teacon.neb.profiler.ChunkSendingEvent;
import org.teacon.neb.profiler.ProfilerChannel;
import org.teacon.neb.profiler.Snapshot;

import java.util.Comparator;

public class SimpleProfiler implements ProfilerChannel.IProfiler {
    private static final class Summary {
        public static final long EMPTY = pack(0, 0, Float.NaN);

        private static final int VALUE_MAX = (1 << 28) - 1;

        public static int unpackTransmit(long value) {
            return (int) ((value >>> 36) & VALUE_MAX);
        }

        public static int unpackReceive(long value) {
            return (int) ((value >>> 8) & VALUE_MAX);
        }

        public static float unpackRatio(long value) {
            return (value & 0xFF) / 128f;
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
    private final int[] chunkEvents = new int[ChunkSendingEvent.values().length];

    @Override
    public synchronized void onTransmitPacket(Snapshot snapshot) {
        transmit += snapshot.getTotalSize();
        transmitCompressed += snapshot.getCompressedSize();

        for (Snapshot.Entry entry : snapshot) {
            long summary = this.summary.getOrDefault(entry.getType(), Summary.EMPTY);
            summary = Summary.pack(
                    Summary.unpackTransmit(summary) + entry.getSize(),
                    Summary.unpackReceive(summary),
                    entry.getRatio()
            );
            this.summary.put(entry.getType(), summary);
        }
    }

    @Override
    public synchronized void onReceivePacket(Snapshot snapshot) {
        receive += snapshot.getTotalSize();
        receiveCompressed += snapshot.getCompressedSize();
        for (Snapshot.Entry entry : snapshot) {
            long summary = this.summary.getOrDefault(entry.getType(), Summary.EMPTY);
            summary = Summary.pack(
                    Summary.unpackTransmit(summary),
                    Summary.unpackReceive(summary) + entry.getSize(),
                    entry.getRatio()
            );
            this.summary.put(entry.getType(), summary);
        }
    }

    @Override
    public synchronized void onChunkUpdate(ChunkSendingEvent event) {
        chunkEvents[event.ordinal()]++;
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
            writer.line(entry.getKey(), Summary.unpackTransmit(summary), Summary.unpackReceive(summary), Summary.unpackRatio(summary));
        });

        writer.line()
                .line("### Chunk Events ###");

        for (int i = 0; i < chunkEvents.length; i++) {
            writer.line(ChunkSendingEvent.values()[i].name(), chunkEvents[i]);
        }

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
