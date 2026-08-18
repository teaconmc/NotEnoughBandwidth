package org.teacon.neb.profiler;

import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.jetbrains.annotations.Nullable;
import org.teacon.neb.profiler.impl.PrometheusProfiler;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@EventBusSubscriber
public final class ProfilerChannel {
    public interface IProfiler {
        void onTransmitPacket(Snapshot snapshot);

        void onReceivePacket(Snapshot snapshot);

        void onChunkUpdate(ChunkSendingEvent event);
    }

    public static final ProfilerChannel CLIENT = new ProfilerChannel();
    public static final ProfilerChannel SERVER = new ProfilerChannel();

    static {
        String port = System.getenv("NEB_PROM_PORT");
        if (port != null) {
            SERVER.add(new PrometheusProfiler(Integer.parseUnsignedInt(port)));
        }
    }

    private final SnapshotContext transmitContext = new SnapshotContext(this::onTransmitPacket);
    private final SnapshotContext receiveContext = new SnapshotContext(this::onReceivePacket);

    private volatile IProfiler[] profilers = new IProfiler[0];
    private final Lock READ, WRITE;

    private ProfilerChannel() {
        ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
        READ = rwLock.readLock();
        WRITE = rwLock.writeLock();
    }

    @Nullable
    public static Snapshot prepareSnapshot(boolean encoder, PacketFlow flow) {
        ProfilerChannel channel = encoder == (flow == PacketFlow.CLIENTBOUND) ? SERVER : CLIENT;
        if (channel.profilers.length == 0) {
            return null;
        }
        return Snapshot.prepare(encoder ? channel.transmitContext : channel.receiveContext);
    }

    @SuppressWarnings("unchecked")
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

    @SuppressWarnings("unchecked")
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

    public void removeAll() {
        WRITE.lock();
        try {
            this.profilers = new IProfiler[0];
        } finally {
            WRITE.unlock();
        }
    }

    public void onTransmitPacket(Snapshot snapshot) {
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

    public void onChunkSendingEvent(ChunkSendingEvent event) {
        READ.lock();
        try {
            IProfiler[] profilers = this.profilers;
            for (IProfiler profiler : profilers) {
                profiler.onChunkUpdate(event);
            }
        } finally {
            READ.unlock();
        }
    }

    @EventBusSubscriber(Dist.CLIENT)
    private static final class ClientImpl {
        @SubscribeEvent
        private static void on(ClientPlayerNetworkEvent.LoggingOut event) {
            CLIENT.removeAll();
        }
    }

    @SubscribeEvent
    private static void on(ServerStoppingEvent event) {
        SERVER.removeAll();
    }
}
