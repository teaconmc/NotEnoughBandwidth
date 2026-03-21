package org.teacon.neb.network.chunk.cache;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.debug.DebugEntryNoop;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterDebugEntriesEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.GuiLayer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.teacon.neb.NotEnoughBandwidth;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicReferenceArray;

@EventBusSubscriber
public final class CachedChunkDebugOverlay implements GuiLayer {

    private static final Identifier ID = NotEnoughBandwidth.id("visualize_cached_chunk");

    private CachedChunkDebugOverlay() {
    }

    @SubscribeEvent
    private static void on(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ID, new CachedChunkDebugOverlay());
    }

    @SubscribeEvent
    private static void on(RegisterDebugEntriesEvent event) {
        event.register(ID, new DebugEntryNoop());
    }

    private static final VarHandle CCC_STORAGE, CCC_STORAGE_CHUNKS, CCC_STORAGE_VCX, CCC_STORAGE_VCZ, CCC_STORAGE_CR;

    static {
        try {
            Class<?> storage = Class.forName("net.minecraft.client.multiplayer.ClientChunkCache$Storage");

            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(ClientChunkCache.class, MethodHandles.lookup());
            CCC_STORAGE = lookup.findVarHandle(ClientChunkCache.class, "storage", storage);

            CCC_STORAGE_CHUNKS = lookup.findVarHandle(storage, "chunks", AtomicReferenceArray.class);
            CCC_STORAGE_VCX = lookup.findVarHandle(storage, "viewCenterX", int.class);
            CCC_STORAGE_VCZ = lookup.findVarHandle(storage, "viewCenterZ", int.class);
            CCC_STORAGE_CR = lookup.findVarHandle(storage, "chunkRadius", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // FIXME: Reorganize state API here.
    private static final long TIME_BASE = System.currentTimeMillis();

    public static final Long2LongMap states = new Long2LongOpenHashMap();

    public static final byte STATE_RECEIVE_CHUNK = 1, STATE_RECEIVE_PRESHARED_CHUNK = 2;

    public static long encodeState(byte state) {
        return ((System.currentTimeMillis() - TIME_BASE) << 4) | (state & 0xF);
    }

    @Override
    public void render(@NonNull GuiGraphicsExtractor graphics, @NonNull DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();

        // FIXME: Fucking Mojang use a List to store active debuggers.
        if (!minecraft.debugEntries.getCurrentlyEnabled().contains(ID) || minecraft.level == null || minecraft.player == null) {
            states.clear();
            return;
        }

        Object storage = CCC_STORAGE.getVolatile(minecraft.level.getChunkSource());

        @SuppressWarnings("unchecked")
        AtomicReferenceArray<@Nullable LevelChunk> chunks = (AtomicReferenceArray<LevelChunk>) CCC_STORAGE_CHUNKS.get(storage);
        int viewCenterX = (int) CCC_STORAGE_VCX.getVolatile(storage), viewCenterZ = (int) CCC_STORAGE_VCZ.getVolatile(storage);
        int chunkRadius = (int) CCC_STORAGE_CR.get(storage), viewRange = chunkRadius * 2 + 1;

        final int size = 4, gap = 1;

        int xStart = graphics.guiWidth() - viewRange * (size + gap);
        int yStart = graphics.guiHeight() - viewRange * (size + gap);
        graphics.fill(xStart - gap, yStart - gap, graphics.guiWidth(), graphics.guiHeight(), 0xC0000000);

        ChunkPos chunkPos = minecraft.player.chunkPosition();
        for (int x0 = chunkPos.x() - chunkRadius, x = x0; x < viewRange + chunkRadius; x++) {
            for (int z0 = chunkPos.z() - chunkRadius, z = z0; z < viewRange + chunkRadius; z++) {
                int color;

                long state = states.get(ChunkPos.pack(x, z));
                if (state != 0) {
                    color = switch ((int) (state & 0xF)) {
                        case STATE_RECEIVE_CHUNK -> 0xC000FF00;
                        case STATE_RECEIVE_PRESHARED_CHUNK -> 0xC00000FF;
                        default -> throw new AssertionError();
                    };
                } else if (x == viewCenterX && z == viewCenterZ) {
                    color = 0xC0FF0000;
                } else {
                    LevelChunk chunk = chunks.get(Math.floorMod(z, viewRange) * viewRange + Math.floorMod(x, viewRange));
                    if (chunk != null && chunk.getPos().x() == x && chunk.getPos().z() == z) {
                        color = 0xC0FFFFFF;
                    } else {
                        continue;
                    }
                }

                int xCellStart = xStart + (x - x0) * (size + gap);
                int yCellStart = yStart + (z - z0) * (size + gap);
                graphics.fill(xCellStart, yCellStart, xCellStart + size, yCellStart + size, color);
            }
        }

        ObjectIterator<Long2LongMap.Entry> iterator = Long2LongMaps.fastIterator(states);
        while (iterator.hasNext()) {
            long relativeTime = (iterator.next().getLongValue() >> 4) & 0x0FFFFFFFFFFFFFFFL;
            if (System.currentTimeMillis() >= relativeTime + TIME_BASE + 2000) {
                iterator.remove();
            }
        }
    }
}
