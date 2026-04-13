package org.teacon.neb.network.chunk.debug;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.debug.DebugEntryNoop;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterDebugEntriesEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.GuiLayer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.teacon.neb.NotEnoughBandwidth;
import org.teacon.neb.utils.vm.LookupAccess;

import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicReferenceArray;

@EventBusSubscriber(Dist.CLIENT)
public final class ClientChunkDebugOverlay implements GuiLayer {
    private static final Identifier ID = NotEnoughBandwidth.id("visualize_cached_chunk");

    private ClientChunkDebugOverlay() {
    }

    @SubscribeEvent
    private static void on(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ID, new ClientChunkDebugOverlay());
    }

    @SubscribeEvent
    private static void on(RegisterDebugEntriesEvent event) {
        event.register(ID, new DebugEntryNoop());
    }

    private static final VarHandle CCC_STORAGE, CCC_STORAGE_CHUNKS, CCC_STORAGE_VCX, CCC_STORAGE_VCZ, CCC_STORAGE_CR;

    static {
        try {
            Class<?> storage = Class.forName("net.minecraft.client.multiplayer.ClientChunkCache$Storage");

            CCC_STORAGE = LookupAccess.IMPL_LOOKUP.findVarHandle(ClientChunkCache.class, "storage", storage);

            CCC_STORAGE_CHUNKS = LookupAccess.IMPL_LOOKUP.findVarHandle(storage, "chunks", AtomicReferenceArray.class);
            CCC_STORAGE_VCX = LookupAccess.IMPL_LOOKUP.findVarHandle(storage, "viewCenterX", int.class);
            CCC_STORAGE_VCZ = LookupAccess.IMPL_LOOKUP.findVarHandle(storage, "viewCenterZ", int.class);
            CCC_STORAGE_CR = LookupAccess.IMPL_LOOKUP.findVarHandle(storage, "chunkRadius", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final int CELL_SIZE = 4, CELL_GAP = 1, CELL_STEP = CELL_SIZE + CELL_GAP;

    private static final Component[] HINTS = {
            Component.literal("Vanilla ↑").withColor(ChunkReceivingEvent.VANILLA_REQUEST.getColor()),
            Component.literal("Vanilla ↓").withColor(ChunkReceivingEvent.VANILLA_RECEIVED.getColor()),
            Component.literal("Preshared ↑").withColor(ChunkReceivingEvent.PRESHARED_REQUEST.getColor()),
            Component.literal("Preshared ↓").withColor(ChunkReceivingEvent.PRESHARED_RECEIVED.getColor()),

            Component.literal("LPCC"),
            Component.literal("Loading ⟳").withColor(ChunkReceivingEvent.StaticColors.PRESHARED_LOADING),
            Component.literal("Ready ✓").withColor(ChunkReceivingEvent.StaticColors.PRESHARED_READY),
            Component.literal("Failed x").withColor(ChunkReceivingEvent.StaticColors.PRESHARED_FAILED),
    };

    @Override
    public void render(@NonNull GuiGraphicsExtractor graphics, @NonNull DeltaTracker deltaTracker) {
        // FIXME: Fucking Mojang use a List to store active debuggers.
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.debugEntries.getCurrentlyEnabled().contains(ID) || minecraft.level == null || minecraft.player == null) {
            ChunkReceivingEvent.clear();
            return;
        }

        ProfilerFiller profiler = Profiler.get();
        profiler.push("chunkDebugOverlay");
        ChunkReceivingEvent.tick();

        Object storage = CCC_STORAGE.getVolatile(minecraft.level.getChunkSource());
        @SuppressWarnings("unchecked")
        AtomicReferenceArray<@Nullable LevelChunk> chunks = (AtomicReferenceArray<LevelChunk>) CCC_STORAGE_CHUNKS.get(storage);
        int viewCenterX = (int) CCC_STORAGE_VCX.getVolatile(storage), viewCenterZ = (int) CCC_STORAGE_VCZ.getVolatile(storage);
        int chunkRadius = (int) CCC_STORAGE_CR.get(storage), viewRange = chunkRadius * 2 + 1;

        int xStart = graphics.guiWidth() - viewRange * CELL_STEP;
        int yStart = graphics.guiHeight() - viewRange * CELL_STEP;

        int hintLines = Math.ceilDiv(HINTS.length, 2);
        for (int i = 0; i < hintLines; i++) {
            int y = yStart - CELL_GAP - hintLines * minecraft.font.lineHeight + i * minecraft.font.lineHeight;
            graphics.textRenderer().accept(xStart, y, HINTS[i]);
            graphics.textRenderer().accept((xStart + graphics.guiWidth()) / 2, y, HINTS[i + hintLines]);
        }

        graphics.fill(xStart - CELL_GAP, yStart - CELL_GAP, graphics.guiWidth(), graphics.guiHeight(), 0xC0000000);

        ChunkPos chunkPos = minecraft.player.chunkPosition();
        for (int x0 = chunkPos.x() - chunkRadius, x = x0; x < chunkPos.x() + chunkRadius; x++) {
            for (int z0 = chunkPos.z() - chunkRadius, z = z0; z < chunkPos.z() + chunkRadius; z++) {
                int color = computeColor(x, z, viewCenterX, viewCenterZ, chunks, viewRange);
                if (color != 0) {
                    int xCellStart = xStart + (x - x0) * CELL_STEP;
                    int yCellStart = yStart + (z - z0) * CELL_STEP;
                    graphics.fill(xCellStart, yCellStart, xCellStart + CELL_SIZE, yCellStart + CELL_SIZE, color);
                }
            }
        }

        profiler.pop();
    }

    private static final int ALPHA_CHANNEL = 0xC0000000;

    private int computeColor(int x, int z, int viewCenterX, int viewCenterZ, AtomicReferenceArray<@Nullable LevelChunk> chunks, int viewRange) {
        ChunkReceivingEvent event = ChunkReceivingEvent.get(ChunkPos.pack(x, z));
        if (event != null) {
            return event.getColor() | ALPHA_CHANNEL;
        }

        if (x == viewCenterX && z == viewCenterZ) {
            return ChunkReceivingEvent.StaticColors.VIEW_CENTER | ALPHA_CHANNEL;
        }

        LevelChunk chunk = chunks.get(Math.floorMod(z, viewRange) * viewRange + Math.floorMod(x, viewRange));
        if (chunk != null && chunk.getPos().x() == x && chunk.getPos().z() == z) {
            return ChunkReceivingEvent.StaticColors.LOADED | ALPHA_CHANNEL;
        }

        // TODO: Display status of preshared chunks.
        return 0;
    }
}
