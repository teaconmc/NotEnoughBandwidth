package org.teacon.neb.network.chunk.preshare.data.palette;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Configuration;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.jetbrains.annotations.NotNull;
import org.teacon.neb.utils.vm.VectorSupport;

import java.util.concurrent.locks.Lock;

import static org.teacon.neb.network.chunk.preshare.data.palette.PaletteContainerAccess.allocateDataFrom;
import static org.teacon.neb.network.chunk.preshare.data.palette.PaletteContainerAccess.getBitStorage;
import static org.teacon.neb.network.chunk.preshare.data.palette.PaletteContainerAccess.getConfiguration;
import static org.teacon.neb.network.chunk.preshare.data.palette.PaletteContainerAccess.getData;
import static org.teacon.neb.network.chunk.preshare.data.palette.PaletteContainerAccess.getPalette;
import static org.teacon.neb.network.chunk.preshare.data.palette.PaletteContainerAccess.getStrategy;
import static org.teacon.neb.network.chunk.preshare.data.palette.PaletteContainerAccess.setData;

public record PalettedContainerChange<T>(byte bitsInMemory, byte bitsInStorage, byte[] palette, long[] data) {
    private static final StreamCodec<FriendlyByteBuf, PalettedContainerChange<?>> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BYTE, PalettedContainerChange::bitsInMemory,
            ByteBufCodecs.BYTE, PalettedContainerChange::bitsInStorage,
            ByteBufCodecs.BYTE_ARRAY, PalettedContainerChange::palette,
            ByteBufCodecs.LONG_ARRAY, PalettedContainerChange::data,
            PalettedContainerChange::new
    );

    @SuppressWarnings("unchecked")
    public static <T> StreamCodec<FriendlyByteBuf, PalettedContainerChange<T>> getCodec() {
        return (StreamCodec<FriendlyByteBuf, PalettedContainerChange<T>>) (Object) STREAM_CODEC;
    }

    private static <T> PalettedContainerRO<T> copyResizeLossy(PalettedContainerRO<T> instance, int bits) {
        Object originalData = getData(instance);
        Object resizedData = allocateDataFrom(instance, bits);

        Palette<T> currentPalette = getPalette(resizedData), oldPalette = getPalette(originalData);
        BitStorage currentStorage = getBitStorage(resizedData), oldStorage = getBitStorage(originalData);
        for (int i = 0; i < oldStorage.getSize(); i++) {
            T value = oldPalette.valueFor(oldStorage.get(i));
            int id = PaletteAccess.lookupID(value, currentPalette, 0);
            currentStorage.set(i, id);
        }

        instance = instance.copy();
        setData(instance, resizedData);
        return instance;
    }

    public static <T> PalettedContainerChange<T> from(Lock lock, PalettedContainerRO<T> base, PalettedContainerRO<T> current) {
        lock.lock();
        try {
            Configuration baseConfiguration = getConfiguration(base), currentConfiguration = getConfiguration(current);
            if (baseConfiguration.bitsInMemory() != currentConfiguration.bitsInMemory()) {
                base = copyResizeLossy(base, currentConfiguration.bitsInStorage());
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

    public PalettedContainer<@NotNull T> apply(Lock lock, PalettedContainerRO<T> base) {
        lock.lock();
        try {
            Configuration baseConfiguration = getConfiguration(base);
            if (baseConfiguration.bitsInMemory() != this.bitsInMemory) {
                base = copyResizeLossy(base, this.bitsInStorage);
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
