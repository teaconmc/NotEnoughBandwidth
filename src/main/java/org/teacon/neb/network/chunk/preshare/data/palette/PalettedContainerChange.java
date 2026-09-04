package org.teacon.neb.network.chunk.preshare.data.palette;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Configuration;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.teacon.neb.utils.ScopedArrayAllocator;
import org.teacon.neb.utils.vm.VectorSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;

import static org.teacon.neb.network.chunk.preshare.data.palette.PaletteContainerAccess.allocateDataFrom;
import static org.teacon.neb.network.chunk.preshare.data.palette.PaletteContainerAccess.getBitStorage;
import static org.teacon.neb.network.chunk.preshare.data.palette.PaletteContainerAccess.getConfiguration;
import static org.teacon.neb.network.chunk.preshare.data.palette.PaletteContainerAccess.getData;
import static org.teacon.neb.network.chunk.preshare.data.palette.PaletteContainerAccess.getPalette;
import static org.teacon.neb.network.chunk.preshare.data.palette.PaletteContainerAccess.getStrategy;
import static org.teacon.neb.network.chunk.preshare.data.palette.PaletteContainerAccess.setData;

public record PalettedContainerChange<T>(byte bitsInMemory, byte bitsInStorage, byte[] palette, long[] data) {
    public static final StreamCodec<FriendlyByteBuf, List<PalettedContainerChange<?>>> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public List<PalettedContainerChange<?>> decode(FriendlyByteBuf buffer) {
            int count = buffer.readVarInt();
            List<PalettedContainerChange<?>> values = new ArrayList<>(count);

            ByteBuf bits = buffer.readSlice(count * 2);
            for (int i = 0; i < count; i++) {
                values.add(new PalettedContainerChange<>(
                        bits.readByte(), bits.readByte(), buffer.readByteArray(), buffer.readLongArray()
                ));
            }
            return values;
        }

        @Override
        public void encode(FriendlyByteBuf buffer, List<PalettedContainerChange<?>> values) {
            buffer.writeVarInt(values.size());
            for (PalettedContainerChange<?> value : values) {
                buffer.writeByte(value.bitsInMemory);
                buffer.writeByte(value.bitsInStorage);
            }
            for (PalettedContainerChange<?> value : values) {
                buffer.writeByteArray(value.palette);
                buffer.writeLongArray(value.data);
            }
        }
    };

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

            byte[] currentPaletteData = getPaletteData(current), basePaletteData = getPaletteData(base), palette;
            if (currentPaletteData.length != basePaletteData.length) {
                palette = currentPaletteData;
            } else {
                int length = currentPaletteData.length;
                palette = ScopedArrayAllocator.allocateUninitialized(byte[].class, length);
                VectorSupport.xor(currentPaletteData, 0, basePaletteData, 0, palette, 0, length);
            }

            long[] arrayBase = baseStorage.getRaw(), arrayCurrent = currentStorage.getRaw();
            long[] result = ScopedArrayAllocator.allocateUninitialized(long[].class, arrayBase.length);
            VectorSupport.xor(arrayBase, 0, arrayCurrent, 0, result, 0, arrayBase.length);
            return new PalettedContainerChange<>((byte) baseConfiguration.bitsInMemory(), (byte) baseConfiguration.bitsInStorage(), palette, result);
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

            byte[] basePaletteData = getPaletteData(base), paletteData;
            if (basePaletteData.length != this.palette.length) {
                paletteData = this.palette;
            } else {
                int length = this.palette.length;
                paletteData = ScopedArrayAllocator.allocateUninitialized(byte[].class, length);
                VectorSupport.xor(basePaletteData, 0, this.palette, 0, paletteData, 0, length);
            }

            getPalette(current).read(new FriendlyByteBuf(Unpooled.wrappedBuffer(paletteData)), getStrategy(current).globalMap());
            VectorSupport.xor(baseStorage.getRaw(), 0, this.data, 0, currentStorage.getRaw(), 0, currentStorage.getRaw().length);
            return current;
        } finally {
            lock.unlock();
        }
    }

    private static <T> byte @NonNull [] getPaletteData(PalettedContainerRO<T> current) {
        byte[] paletteData;
        FriendlyByteBuf paletteBuffer = new FriendlyByteBuf(PooledByteBufAllocator.DEFAULT.directBuffer(1024));
        try {
            getPalette(current).write(paletteBuffer, getStrategy(current).globalMap());

            paletteData = ScopedArrayAllocator.allocateUninitialized(byte[].class, paletteBuffer.readableBytes());
            paletteBuffer.readBytes(paletteData);
        } finally {
            paletteBuffer.release();
        }
        return paletteData;
    }
}
