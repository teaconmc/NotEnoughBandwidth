package org.teacon.neb.network.chunk.preshare.data.palette;

import com.mojang.logging.LogUtils;
import net.minecraft.util.CrudeIncrementalIntIdentityHashBiMap;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;
import net.minecraft.world.level.chunk.SingleValuePalette;
import org.slf4j.Logger;
import org.teacon.neb.utils.vm.LookupAccess;

import java.lang.invoke.VarHandle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PaletteAccess {
    private PaletteAccess() {
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final VarHandle HM_VALUES, L_VALUES, L_SIZE, SV_VALUE;
    private static final Set<Class<?>> WARNED = ConcurrentHashMap.newKeySet();

    static {
        try {
            HM_VALUES = LookupAccess.IMPL_LOOKUP.findVarHandle(
                    HashMapPalette.class, "values", CrudeIncrementalIntIdentityHashBiMap.class
            );

            L_VALUES = LookupAccess.IMPL_LOOKUP.findVarHandle(
                    LinearPalette.class, "values", Object[].class
            );
            L_SIZE = LookupAccess.IMPL_LOOKUP.findVarHandle(
                    LinearPalette.class, "size", int.class
            );

            SV_VALUE = LookupAccess.IMPL_LOOKUP.findVarHandle(
                    SingleValuePalette.class, "value", Object.class
            );
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> int lookupID(T value, Palette<T> palette, int defaultValue) {
        switch (palette) {
            case HashMapPalette<T> _ -> {
                CrudeIncrementalIntIdentityHashBiMap<T> map = (CrudeIncrementalIntIdentityHashBiMap<T>) HM_VALUES.get(palette);
                int v = map.getId(value);
                return v != -1 ? v : defaultValue;
            }
            case GlobalPalette<T> _ -> {
                return palette.idFor(value, PaletteResize.noResizeExpected());
            }
            case LinearPalette<T> _ -> {
                T[] values = (T[]) L_VALUES.get(palette);
                int size = (int) L_SIZE.get(palette);

                for (int i = 0; i < size; i++) {
                    if (values[i] == value) {
                        return i;
                    }
                }
                return defaultValue;
            }
            case SingleValuePalette<T> _ -> {
                T v = (T) SV_VALUE.get(palette);
                return v == value ? 0 : defaultValue;
            }
            default -> {
                if (WARNED.add(value.getClass())) {
                    LOGGER.warn("Unknown Palette implementation: {}. There may be performance issue!", value.getClass());
                }
                return palette.maybeHas(v -> v == value) ? palette.idFor(value, PaletteResize.noResizeExpected()) : defaultValue;
            }
        }
    }
}
