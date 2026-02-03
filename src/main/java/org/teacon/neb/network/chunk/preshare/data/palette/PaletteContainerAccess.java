package org.teacon.neb.network.chunk.preshare.data.palette;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Configuration;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import org.teacon.neb.utils.vm.LookupAccess;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

@SuppressWarnings("unchecked")
public final class PaletteContainerAccess {
    private PaletteContainerAccess() {
    }

    private static final MethodHandle ALLOC_DATA;
    private static final VarHandle DATA;
    private static final VarHandle STRATEGY;
    private static final VarHandle CONFIGURATION;
    private static final VarHandle BIT_STORAGE;
    private static final VarHandle PALETTE;

    static {
        try {
            final Class<?> data = Class.forName("net.minecraft.world.level.chunk.PalettedContainer$Data");

            ALLOC_DATA = LookupAccess.IMPL_LOOKUP.findVirtual(PalettedContainer.class, "createOrReuseData", MethodType.methodType(data, data, int.class))
                    .asType(MethodType.methodType(Object.class, PalettedContainerRO.class, Object.class, int.class));

            DATA = LookupAccess.IMPL_LOOKUP.findVarHandle(PalettedContainer.class, "data", data);
            STRATEGY = LookupAccess.IMPL_LOOKUP.findVarHandle(PalettedContainer.class, "strategy", Strategy.class);
            CONFIGURATION = LookupAccess.IMPL_LOOKUP.findVarHandle(data, "configuration", Configuration.class);
            BIT_STORAGE = LookupAccess.IMPL_LOOKUP.findVarHandle(data, "storage", BitStorage.class);
            PALETTE = LookupAccess.IMPL_LOOKUP.findVarHandle(data, "palette", Palette.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static Configuration getConfiguration(PalettedContainerRO<?> instance) {
        return (Configuration) CONFIGURATION.get(DATA.get(instance));
    }

    public static <T> Strategy<T> getStrategy(PalettedContainerRO<T> instance) {
        return (Strategy<T>) STRATEGY.get(instance);
    }

    public static BitStorage getBitStorage(PalettedContainerRO<?> instance) {
        return (BitStorage) BIT_STORAGE.get(DATA.get(instance));
    }

    public static BitStorage getBitStorage(Object data) {
        return (BitStorage) BIT_STORAGE.get(data);
    }

    public static <T> Palette<T> getPalette(PalettedContainerRO<T> instance) {
        return (Palette<T>) PALETTE.get(DATA.get(instance));
    }

    public static <T> Palette<T> getPalette(Object data) {
        return (Palette<T>) PALETTE.get(data);
    }

    public static Object getData(PalettedContainerRO<?> instance) {
        return DATA.get(instance);
    }

    public static void setData(PalettedContainerRO<?> instance, Object data) {
        DATA.set(instance, data);
    }

    public static Object allocateDataFrom(PalettedContainerRO<?> container, int bits) {
        try {
            return ALLOC_DATA.invokeExact(container, DATA.get(container), bits);
        } catch (Throwable e) {
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }
}
