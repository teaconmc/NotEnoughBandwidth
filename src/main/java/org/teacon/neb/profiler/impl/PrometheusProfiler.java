package org.teacon.neb.profiler.impl;

import com.mojang.logging.LogUtils;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.HTTPServer;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.slf4j.Logger;
import org.teacon.neb.profiler.ProfilerChannel;
import org.teacon.neb.profiler.Snapshot;

import java.io.IOException;

public final class PrometheusProfiler implements ProfilerChannel.IProfiler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Counter TRANSMIT = Counter.build("neb_sent_total", "Total size of sent packets.").labelNames("id").register();
    private final Counter TRANSMIT_COMPRESSED = Counter.build("neb_sent_compressed_bytes_total", "Total size (compressed) of sent packets.").register();
    private final Counter RECEIVE = Counter.build("neb_received_total", "Total size of received packets.").labelNames("id").register();
    private final Counter RECEIVE_COMPRESSED = Counter.build("neb_received_compressed_bytes_total", "Total size (compressed) of received packets.").register();
    private final Gauge COMPRESSIBILITY = Gauge.build("neb_compressibility", "The compressibility of all transmit/received packets.").labelNames("id").register();

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
        for (Object2LongMap.Entry<String> entry : snapshot) {
            String packetType = Snapshot.getType(entry);
            COMPRESSIBILITY.labels(packetType).set(Snapshot.getCompressibility(entry));
            TRANSMIT.labels(packetType).inc(Snapshot.getSize(entry));
        }
    }

    @Override
    public void onReceivePacket(Snapshot snapshot) {
        RECEIVE_COMPRESSED.inc(snapshot.getCompressedSize());
        for (Object2LongMap.Entry<String> entry : snapshot) {
            String packetType = Snapshot.getType(entry);
            COMPRESSIBILITY.labels(packetType).set(Snapshot.getCompressibility(entry));
            RECEIVE.labels(packetType).inc(Snapshot.getSize(entry));
        }
    }
}
