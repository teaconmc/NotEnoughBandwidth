package org.teacon.neb.network.chunk.debug;

import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

public enum ChunkReceivingEvent {
    VANILLA_REQUEST(0xD3F1A7),
    VANILLA_RECEIVED(0x94C748),

    PRESHARED_REQUEST(0xC6EDFB),
    PRESHARED_RECEIVED(0x6CC3E0);

    public static final class StaticColors {
        public static final int
                VIEW_CENTER = 0xC97CF4,
                LOADED = 0xFFFFFF,
                PRESHARED_LOADING = 0xFDD0EC,
                PRESHARED_READY = 0xE774BB,
                PRESHARED_FAILED = 0xE63B1B;
    }

    private final int color;

    private static final long TIME_BASE = System.currentTimeMillis() - 1;
    private static final long FLASH_TIME = 1200;

    private static final ChunkReceivingEvent[] VALUES = values();
    private static final long EVENT_BITS = 64 - Long.numberOfLeadingZeros(VALUES.length - 1);
    private static final long TIME_MASK = (1L << (64 - EVENT_BITS)) - 1, EVENT_MASK = (1L << EVENT_BITS) - 1;

    ChunkReceivingEvent(int color) {
        this.color = color;
    }

    private static long pack(long time, ChunkReceivingEvent event) {
        return ((time - TIME_BASE) << EVENT_BITS) | event.ordinal();
    }

    private static long unpackTime(long value) {
        return ((value >> EVENT_BITS) & TIME_MASK) + TIME_BASE;
    }

    private static ChunkReceivingEvent unpackEvent(long value) {
        return VALUES[(int) (value & EVENT_MASK)];
    }

    private static final Long2LongLinkedOpenHashMap values = new Long2LongLinkedOpenHashMap();

    public void submit(long pos) {
        validateThread();

        values.putAndMoveToLast(pos, pack(System.currentTimeMillis(), this));
    }

    public int getColor() {
        return color;
    }

    public static void tick() {
        validateThread();

        LongIterator iterator = values.values().longIterator();
        while (iterator.hasNext()) {
            if (unpackTime(iterator.nextLong()) + FLASH_TIME <= System.currentTimeMillis()) {
                iterator.remove();
            } else {
                break;
            }
        }
    }

    public static void clear() {
        validateThread();

        values.clear();
    }

    @Nullable
    public static ChunkReceivingEvent get(long pos) {
        validateThread();

        long v = values.get(pos);
        return v == 0 ? null : unpackEvent(v);
    }

    private static void validateThread() {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("ChunkReceivingEvent#submit called from wrong thread: " + Thread.currentThread());
        }
    }
}
