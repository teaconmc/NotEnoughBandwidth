package org.teacon.neb.profiler.impl;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.teacon.neb.profiler.ProfilerChannel;
import org.teacon.neb.profiler.Snapshot;

import java.util.Comparator;

public class SimpleProfiler implements ProfilerChannel.IProfiler {
    private static final class Summary {
        public int transmit, receive;
        public float compressibility;
    }

    private final long startTime = System.currentTimeMillis();
    private int transmit, receive, transmitCompressed, receiveCompressed;
    private final Object2ObjectMap<String, Summary> summary = new Object2ObjectOpenHashMap<>();

    @Override
    public synchronized void onTransmitPacket(Snapshot snapshot) {
        transmit += snapshot.getTotalSize();
        transmitCompressed += snapshot.getCompressedSize();
        for (Object2LongMap.Entry<String> entry : snapshot) {
            Summary summary = this.summary.computeIfAbsent(Snapshot.getType(entry), _ -> new Summary());
            summary.transmit += Snapshot.getSize(entry);
            summary.compressibility = Snapshot.getCompressibility(entry);
        }
    }

    @Override
    public synchronized void onReceivePacket(Snapshot snapshot) {
        receive += snapshot.getTotalSize();
        receiveCompressed += snapshot.getCompressedSize();
        for (Object2LongMap.Entry<String> entry : snapshot) {
            Summary summary = this.summary.computeIfAbsent(Snapshot.getType(entry), _ -> new Summary());
            summary.receive += Snapshot.getSize(entry);
            summary.compressibility = Snapshot.getCompressibility(entry);
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

        summary.object2ObjectEntrySet().stream().sorted(Comparator.comparing(Object2ObjectMap.Entry::getKey)).forEach(entry -> {
            Summary summary = entry.getValue();
            writer.line(entry.getKey(), summary.transmit, summary.receive, summary.compressibility);
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
