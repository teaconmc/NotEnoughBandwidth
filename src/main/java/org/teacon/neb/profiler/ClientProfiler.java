package org.teacon.neb.profiler;

import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterDebugEntriesEvent;
import org.jspecify.annotations.NonNull;
import org.teacon.neb.NotEnoughBandwidth;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

@EventBusSubscriber(Dist.CLIENT)
public final class ClientProfiler implements ProfilerChannel.IProfiler {
    private final LongAdder tx = new LongAdder(), rx = new LongAdder(),
            txIO = new LongAdder(), rxIO = new LongAdder();

    private /* value */ record Usage(long timestamp, long tx, long rx, long txIO, long rxIO) {
    }

    private Usage previous = new Usage(-1, 0,0, 0, 0), cached = previous;

    private ClientProfiler() {
    }

    private static ClientProfiler instance;

    @SubscribeEvent
    private static void on(ClientPlayerNetworkEvent.LoggingIn event) {
        ProfilerChannel.CLIENT.add(instance = new ClientProfiler());
    }

    @SubscribeEvent
    private static void on(ClientPlayerNetworkEvent.LoggingOut event) {
        instance = null;
    }

    @SubscribeEvent
    private static void on(RegisterDebugEntriesEvent event) {
        Identifier id = NotEnoughBandwidth.id("client_profiler");
        event.register(id, (display, _, _, _) -> {
            if (instance != null) {
                instance.updateDisplay(display, id);
            }
        });
    }

    private void updateDisplay(DebugScreenDisplayer display, Identifier id) {
        long tx = this.tx.sum(), txIO = this.txIO.sum(), rx = this.rx.sum(), rxIO = this.rxIO.sum();
        long txDelta = tx - previous.tx, txIODelta = txIO - previous.txIO, rxDelta = rx - previous.rx, rxIODelta = rxIO - previous.rxIO;

        long duration, now = System.currentTimeMillis();
        if (previous.timestamp == -1) {
            cached = previous = new Usage(now, 0, 0, 0, 0);
            duration = 0;
        } else {
            duration = now - previous.timestamp;
        }

        display.addToGroup(id, List.of(
                String.format("[TX] raw=%s io=%s rate=%s ratio=%s", toKB(tx), toKB(txIO), toSpeed(txIODelta, duration), toRatio(txDelta, txIODelta)),
                String.format("[RX] raw=%s io=%s rate=%s ratio=%s", toKB(rx), toKB(rxIO), toSpeed(rxIODelta, duration), toRatio(rxDelta, rxIODelta))
        ));

        if (now - cached.timestamp >= 5000) {
            previous = cached;
            cached = new Usage(now, tx, rx, txIO, rxIO);
        }
    }

    private static @NonNull String toKB(long txCompressed) {
        return txCompressed / 1024 + "KB";
    }

    private static @NonNull String toRatio(long raw, long io) {
        return String.format("%.2f%%", io / (double) raw);
    }

    private static @NonNull String toSpeed(long txCompressed, long duration) {
        if (duration == 0) {
            return "NaN B/s";
        }
        return txCompressed * 1000 / duration + "B/s";
    }

    @Override
    public void onTransmitPacket(Snapshot snapshot) {
        tx.add(snapshot.getTotalSize());
        txIO.add(snapshot.getCompressedSize());
    }

    @Override
    public void onReceivePacket(Snapshot snapshot) {
        rx.add(snapshot.getTotalSize());
        rxIO.add(snapshot.getCompressedSize());
    }
}
