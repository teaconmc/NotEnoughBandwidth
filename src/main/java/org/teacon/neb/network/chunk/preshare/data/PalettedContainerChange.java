package org.teacon.neb.network.chunk.preshare.data;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Configuration;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.jetbrains.annotations.NotNull;
import org.teacon.neb.utils.vm.LookupAccess;
import org.teacon.neb.utils.vm.VectorSupport;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.Lock;

public record PalettedContainerChange(byte bitsInMemory, byte bitsInStorage, long[] data) {
    public static final StreamCodec<@NotNull FriendlyByteBuf, @NotNull PalettedContainerChange> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BYTE, PalettedContainerChange::bitsInMemory,
            ByteBufCodecs.BYTE, PalettedContainerChange::bitsInStorage,
            ByteBufCodecs.LONG_ARRAY, PalettedContainerChange::data,
            PalettedContainerChange::new
    );

    private static final MethodHandle RESIZE, COPY_FROM;
    private static final VarHandle DATA, CONFIGURATION, BIT_STORAGE, PALETTE;

    static {
        try {
            // DO NOT REMOVE THIS FINAL MARK! OR IDEA JETBRAINS WILL NO LONGER PROVIDE GRAMMAR HIGHLIGHT!
            final Class<?> data = Class.forName("net.minecraft.world.level.chunk.PalettedContainer$Data");

            RESIZE = LookupAccess.IMPL_LOOKUP.findVirtual(PalettedContainer.class, "createOrReuseData", MethodType.methodType(data, data, int.class))
                    .asType(MethodType.methodType(Object.class, PalettedContainer.class, Object.class, int.class));

            COPY_FROM = LookupAccess.IMPL_LOOKUP.findVirtual(data, "copyFrom", MethodType.methodType(void.class, Palette.class, BitStorage.class))
                    .asType(MethodType.methodType(void.class, Object.class, Palette.class, BitStorage.class));

            DATA = LookupAccess.IMPL_LOOKUP.findVarHandle(PalettedContainer.class, "data", data);
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

    private static BitStorage getBitStorage(PalettedContainer<?> instance) {
        return (BitStorage) BIT_STORAGE.get(DATA.get(instance));
    }

    private static void resize(PalettedContainer<?> instance, int bits) {
        try {
            Object originalData = DATA.get(instance);
            Object resizedData = RESIZE.invokeExact(instance, originalData, bits);
            COPY_FROM.invokeExact(resizedData, (Palette<?>) PALETTE.get(originalData), (BitStorage) BIT_STORAGE.get(originalData));
            DATA.set(instance, resizedData);
        } catch (Throwable e) {
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    public static <T> PalettedContainerChange from(Lock lock, PalettedContainerRO<@NotNull T> base, PalettedContainerRO<@NotNull T> current) {
        return from(lock, (PalettedContainer<@NotNull T>) base, (PalettedContainer<@NotNull T>) current);
    }

    public static <T> PalettedContainerChange from(Lock lock, PalettedContainer<@NotNull T> base, PalettedContainer<@NotNull T> current) {
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

            long[] arrayBase = baseStorage.getRaw(), arrayCurrent = currentStorage.getRaw();
            long[] result = new long[arrayBase.length];
            VectorSupport.xor(arrayBase, 0, arrayCurrent, 0, result, 0, arrayBase.length);
            return new PalettedContainerChange((byte) baseConfiguration.bitsInMemory(), (byte) baseConfiguration.bitsInStorage(), result);
        } finally {
            lock.unlock();
        }
    }

    public <T> PalettedContainer<@NotNull T> apply(Lock lock, PalettedContainerRO<@NotNull T> base) {
        return apply(lock, (PalettedContainer<@NotNull T>) base);
    }

    public <T> PalettedContainer<@NotNull T> apply(Lock lock, PalettedContainer<@NotNull T> base) {
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

            VectorSupport.xor(baseStorage.getRaw(), 0, this.data, 0, currentStorage.getRaw(), 0, currentStorage.getRaw().length);
            return base;
        } finally {
            lock.unlock();
        }
    }
}
