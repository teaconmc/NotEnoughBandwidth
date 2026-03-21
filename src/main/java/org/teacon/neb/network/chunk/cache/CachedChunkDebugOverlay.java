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
import org.intellij.lang.annotations.MagicConstant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.teacon.neb.NotEnoughBandwidth;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicReferenceArray;

@EventBusSubscriber
public final class CachedChunkDebugOverlay implements GuiLayer {
    public static final byte STATE_RECEIVE_CHUNK = 1, STATE_RECEIVE_PRESHARED_CHUNK = 2;

    public static void mark(
            long chunk,
            @MagicConstant(flags = {STATE_RECEIVE_CHUNK, STATE_RECEIVE_PRESHARED_CHUNK}) byte state
    ) {
        states.put(chunk, encodeState(state));
    }

    private static final long TIME_BASE = System.currentTimeMillis();
    private static final Long2LongMap states = new Long2LongOpenHashMap();

    public static long encodeState(byte state) {
        return ((System.currentTimeMillis() - TIME_BASE) << 4) | (state & 0xF);
    }

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

    private static final int CELL_SIZE = 4, CELL_GAP = 1, CELL_STEP = CELL_SIZE + CELL_GAP;

    @Override
    public void render(@NonNull GuiGraphicsExtractor graphics, @NonNull DeltaTracker deltaTracker) {
        // FIXME: Fucking Mojang use a List to store active debuggers.
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.debugEntries.getCurrentlyEnabled().contains(ID) || minecraft.level == null || minecraft.player == null) {
            states.clear();
            return;
        }

        Object storage = CCC_STORAGE.getVolatile(minecraft.level.getChunkSource());

        @SuppressWarnings("unchecked")
        AtomicReferenceArray<@Nullable LevelChunk> chunks = (AtomicReferenceArray<LevelChunk>) CCC_STORAGE_CHUNKS.get(storage);
        int viewCenterX = (int) CCC_STORAGE_VCX.getVolatile(storage), viewCenterZ = (int) CCC_STORAGE_VCZ.getVolatile(storage);
        int chunkRadius = (int) CCC_STORAGE_CR.get(storage), viewRange = chunkRadius * 2 + 1;

        int xStart = graphics.guiWidth() - viewRange * CELL_STEP;
        int yStart = graphics.guiHeight() - viewRange * CELL_STEP;
        graphics.fill(xStart - CELL_GAP, yStart - CELL_GAP, graphics.guiWidth(), graphics.guiHeight(), 0xC0000000);

        ChunkPos chunkPos = minecraft.player.chunkPosition();
        for (int x0 = chunkPos.x() - chunkRadius, x = x0; x < viewRange + chunkRadius; x++) {
            for (int z0 = chunkPos.z() - chunkRadius, z = z0; z < viewRange + chunkRadius; z++) {
                int color = computeColor(x, z, viewCenterX, viewCenterZ, chunks, viewRange);
                if (color != 0) {
                    int xCellStart = xStart + (x - x0) * CELL_STEP;
                    int yCellStart = yStart + (z - z0) * CELL_STEP;
                    graphics.fill(xCellStart, yCellStart, xCellStart + CELL_SIZE, yCellStart + CELL_SIZE, color);
                }
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

    private int computeColor(int x, int z, int viewCenterX, int viewCenterZ, AtomicReferenceArray<@Nullable LevelChunk> chunks, int viewRange) {
        long state = states.get(ChunkPos.pack(x, z));
        if (state != 0) {
            return switch ((int) (state & 0xF)) {
                case STATE_RECEIVE_CHUNK -> 0xC000FF00;
                case STATE_RECEIVE_PRESHARED_CHUNK -> 0xC00000FF;
                default -> throw new AssertionError();
            };
        }

        if (x == viewCenterX && z == viewCenterZ) {
            return 0xC0FF0000;
        }

        LevelChunk chunk = chunks.get(Math.floorMod(z, viewRange) * viewRange + Math.floorMod(x, viewRange));
        if (chunk != null && chunk.getPos().x() == x && chunk.getPos().z() == z) {
            return 0xC0FFFFFF;
        }

        return 0;
    }
}
