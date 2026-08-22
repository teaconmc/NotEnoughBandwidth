package org.teacon.neb.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Objects;

public interface SidedDelegate {
    boolean isSameThread();

    boolean isPaused();

    void execute(Runnable runnable);

    SidedDelegate CLIENT = FMLEnvironment.getDist().isDedicatedServer() ? null : (SidedDelegate) new SidedDelegate() {
        @Override
        public boolean isSameThread() {
            return Minecraft.getInstance().isSameThread();
        }

        @Override
        public boolean isPaused() {
            return Minecraft.getInstance().isPaused();
        }

        @Override
        public void execute(Runnable runnable) {
            Minecraft.getInstance().execute(runnable);
        }
    };

    SidedDelegate SERVER = new SidedDelegate() {
        @Override
        public boolean isSameThread() {
            return Objects.requireNonNull(ServerLifecycleHooks.getCurrentServer()).isSameThread();
        }

        @Override
        public boolean isPaused() {
            return Objects.requireNonNull(ServerLifecycleHooks.getCurrentServer()).isPaused();
        }

        @Override
        public void execute(Runnable runnable) {
            Objects.requireNonNull(ServerLifecycleHooks.getCurrentServer()).execute(runnable);
        }
    };

    static SidedDelegate select(Connection connection) {
        return switch (connection.getSending()) {
            case CLIENTBOUND -> SERVER;
            case SERVERBOUND -> CLIENT;
        };
    }
}
