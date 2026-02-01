package org.teacon.neb.network.chunk.preshare.data;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Configuration;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import org.jetbrains.annotations.NotNull;
import org.teacon.neb.utils.vm.LookupAccess;
import org.teacon.neb.utils.vm.VectorSupport;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.Lock;

public record PalettedContainerChange<T>(byte bitsInMemory, byte bitsInStorage, byte[] palette, long[] data) {
    private static final StreamCodec<@NotNull FriendlyByteBuf, @NotNull PalettedContainerChange<?>> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BYTE, PalettedContainerChange::bitsInMemory,
            ByteBufCodecs.BYTE, PalettedContainerChange::bitsInStorage,
            ByteBufCodecs.BYTE_ARRAY, PalettedContainerChange::palette,
            ByteBufCodecs.LONG_ARRAY, PalettedContainerChange::data,
            PalettedContainerChange::new
    );

    @SuppressWarnings("unchecked")
    public static <T> StreamCodec<@NotNull FriendlyByteBuf, @NotNull PalettedContainerChange<T>> getCodec() {
        return (StreamCodec<@NotNull FriendlyByteBuf, @NotNull PalettedContainerChange<T>>) (Object) STREAM_CODEC;
    }

    private static final MethodHandle RESIZE;
    private static final VarHandle DATA, STRATEGY, CONFIGURATION, BIT_STORAGE, PALETTE;

    static {
        try {
            // DO NOT REMOVE THIS FINAL MARK! OR IDEA JETBRAINS WILL NO LONGER PROVIDE GRAMMAR HIGHLIGHT!
            final Class<?> data = Class.forName("net.minecraft.world.level.chunk.PalettedContainer$Data");

            RESIZE = LookupAccess.IMPL_LOOKUP.findVirtual(PalettedContainer.class, "createOrReuseData", MethodType.methodType(data, data, int.class))
                    .asType(MethodType.methodType(Object.class, PalettedContainer.class, Object.class, int.class));

            DATA = LookupAccess.IMPL_LOOKUP.findVarHandle(PalettedContainer.class, "data", data);
            STRATEGY = LookupAccess.IMPL_LOOKUP.findVarHandle(PalettedContainer.class, "strategy", Strategy.class);
            CONFIGURATION = LookupAccess.IMPL_LOOKUP.findVarHandle(data, "configuration", Configuration.class);
            BIT_STORAGE = LookupAccess.IMPL_LOOKUP.findVarHandle(data, "storage", BitStorage.class);
            PALETTE = LookupAccess.IMPL_LOOKUP.findVarHandle(data, "palette", Palette.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Configuration getConfiguration(PalettedContainer<?> instance) {
        return (Configuration) CONFIGURATION.get(DATA.get(instance));
    }

    @SuppressWarnings("unchecked")
    private static <T> Strategy<@NotNull T> getStrategy(PalettedContainer<@NotNull T> instance) {
        return (Strategy<@NotNull T>) STRATEGY.get(instance);
    }

    private static BitStorage getBitStorage(PalettedContainer<?> instance) {
        return (BitStorage) BIT_STORAGE.get(DATA.get(instance));
    }

    private static BitStorage getBitStorage(Object data) {
        return (BitStorage) BIT_STORAGE.get(data);
    }

    @SuppressWarnings("unchecked")
    private static <T> Palette<@NotNull T> getPalette(PalettedContainer<@NotNull T> instance) {
        return (Palette<@NotNull T>) PALETTE.get(DATA.get(instance));
    }

    @SuppressWarnings("unchecked")
    private static <T> Palette<@NotNull T> getPalette(Object data) {
        return (Palette<@NotNull T>) PALETTE.get(data);
    }

    private static final NullPointerException NPE = new NullPointerException("INTERNAL IMPLEMENTATION");

    private static <T> void resize(PalettedContainer<?> instance, int bits) {
        try {
            Object originalData = DATA.get(instance);
            Object resizedData = RESIZE.invokeExact(instance, originalData, bits);

            Palette<@NotNull T> currenpalette = getPalette(resizedData), oldPalette = getPalette(originalData);
            BitStorage currentStorage = getBitStorage(resizedData), oldStorage = getBitStorage(originalData);

            for (int i = 0; i < oldStorage.getSize(); i++) {
                T value = oldPalette.valueFor(oldStorage.get(i));

                int id = 0;
                try {
                    id = currenpalette.idFor(value, (_, _) -> {
                        throw NPE;
                    });
                } catch (NullPointerException e) {
                    if (e != NPE) {
                        throw e;
                    }
                }
                currentStorage.set(i, id);
            }

            DATA.set(instance, resizedData);
        } catch (Throwable e) {
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    public static <T> PalettedContainerChange<T> from(Lock lock, PalettedContainerRO<@NotNull T> base, PalettedContainerRO<@NotNull T> current) {
        return from(lock, (PalettedContainer<@NotNull T>) base, (PalettedContainer<@NotNull T>) current);
    }

    public static <T> PalettedContainerChange<T> from(Lock lock, PalettedContainer<@NotNull T> base, PalettedContainer<@NotNull T> current) {
        lock.lock();
        try {
            Configuration baseConfiguration = getConfiguration(base), currentConfiguration = getConfiguration(current);
            if (baseConfiguration.bitsInMemory() != currentConfiguration.bitsInMemory()) {
                resize(base, currentConfiguration.bitsInStorage());
                baseConfiguration = getConfiguration(base);
            }

            BitStorage baseStorage = getBitStorage(base), currentStorage = getBitStorage(current);
            if (baseStorage.getBits() != currentStorage.getBits() || baseStorage.getSize() != currentStorage.getSize()) {
                throw new AssertionError("Failed to resize PalettedContainer");
            }

            byte[] paletteData;
            FriendlyByteBuf paletteBuffer = new FriendlyByteBuf(Unpooled.directBuffer());
            try {
                getPalette(current).write(paletteBuffer, getStrategy(current).globalMap());

                paletteData = new byte[paletteBuffer.readableBytes()];
                paletteBuffer.readBytes(paletteData);
            } finally {
                paletteBuffer.release();
            }

            long[] arrayBase = baseStorage.getRaw(), arrayCurrent = currentStorage.getRaw();
            long[] result = new long[arrayBase.length];
            VectorSupport.xor(arrayBase, 0, arrayCurrent, 0, result, 0, arrayBase.length);
            return new PalettedContainerChange<>((byte) baseConfiguration.bitsInMemory(), (byte) baseConfiguration.bitsInStorage(), paletteData, result);
        } finally {
            lock.unlock();
        }
    }

    public PalettedContainer<@NotNull T> apply(Lock lock, PalettedContainerRO<@NotNull T> base) {
        return apply(lock, (PalettedContainer<@NotNull T>) base);
    }

    public PalettedContainer<@NotNull T> apply(Lock lock, PalettedContainer<@NotNull T> base) {
        lock.lock();
        try {
            Configuration baseConfiguration = getConfiguration(base);
            if (baseConfiguration.bitsInMemory() != this.bitsInMemory) {
                resize(base, this.bitsInStorage);
            }

            PalettedContainer<@NotNull T> current = base.copy();
            BitStorage baseStorage = getBitStorage(base), currentStorage = getBitStorage(current);
            if (baseStorage.getBits() != this.bitsInMemory) {
                throw new AssertionError("Failed to resize PalettedContainer");
            }

            getPalette(current).read(new FriendlyByteBuf(Unpooled.wrappedBuffer(this.palette)), getStrategy(current).globalMap());
            VectorSupport.xor(baseStorage.getRaw(), 0, this.data, 0, currentStorage.getRaw(), 0, currentStorage.getRaw().length);
            return current;
        } finally {
            lock.unlock();
        }
    }
}
