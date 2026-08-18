package org.teacon.neb.profiler.impl;

import com.google.common.base.CaseFormat;
import com.mojang.logging.LogUtils;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.HTTPServer;
import org.slf4j.Logger;
import org.teacon.neb.profiler.ChunkSendingEvent;
import org.teacon.neb.profiler.ProfilerChannel;
import org.teacon.neb.profiler.Snapshot;

import java.io.IOException;
import java.util.Arrays;

public final class PrometheusProfiler implements ProfilerChannel.IProfiler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Counter TRANSMIT = Counter.build("neb_sent_total", "Total size of sent packets.").labelNames("id").register();
    private final Counter TRANSMIT_COMPRESSED = Counter.build("neb_sent_compressed_bytes_total", "Total size (compressed) of sent packets.").register();
    private final Counter RECEIVE = Counter.build("neb_received_total", "Total size of received packets.").labelNames("id").register();
    private final Counter RECEIVE_COMPRESSED = Counter.build("neb_received_compressed_bytes_total", "Total size (compressed) of received packets.").register();
    private final Gauge COMPRESSIBILITY = Gauge.build("neb_compressibility", "The compressibility of all transmit/received packets.").labelNames("id").register();
    private final Counter[] CHUNK_EVENTS = Arrays.stream(ChunkSendingEvent.values())
            .map(event ->
                    Counter.build(
                            "neb_chunk_event_" + CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_UNDERSCORE, event.name()),
                            "Counter of " + event.name()
                    ).register()
            )
            .toArray(Counter[]::new);

    public PrometheusProfiler(int port) {
        HTTPServer.Builder builder = new HTTPServer.Builder()
                .withPort(port)
                .withDaemonThreads(true);

        try {
            builder.build();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        LOGGER.info("Prometheus dashboard has started on port: {}", port);
    }

    @Override
    public void onTransmitPacket(Snapshot snapshot) {
        TRANSMIT_COMPRESSED.inc(snapshot.getCompressedSize());
        for (Snapshot.Entry entry : snapshot) {
            COMPRESSIBILITY.labels(entry.getType()).set(entry.getRatio());
            TRANSMIT.labels(entry.getType()).inc(entry.getSize());
        }
    }

    @Override
    public void onReceivePacket(Snapshot snapshot) {
        RECEIVE_COMPRESSED.inc(snapshot.getCompressedSize());
        for (Snapshot.Entry entry : snapshot) {
            COMPRESSIBILITY.labels(entry.getType()).set(entry.getRatio());
            RECEIVE.labels(entry.getType()).inc(entry.getSize());
        }
    }

    @Override
    public void onChunkUpdate(ChunkSendingEvent event) {
        CHUNK_EVENTS[event.ordinal()].inc();
    }
}
