package org.teacon.neb.utils;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class ConfigAccess {
    private ConfigAccess() {
    }

    private static final VarHandle SPEC;

    static {
        try {
            SPEC = MethodHandles.privateLookupIn(ModConfigSpec.ConfigValue.class, MethodHandles.lookup())
                    .findVarHandle(ModConfigSpec.ConfigValue.class, "spec", ModConfigSpec.class)
                    .withInvokeExactBehavior();
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static boolean isInitialized(ModConfigSpec.ConfigValue<?> config) {
        ModConfigSpec spec = (ModConfigSpec) SPEC.get(config);
        return spec.isLoaded();
    }

    public static <T> T getOrDefault(ModConfigSpec.ConfigValue<T> config, T defaultValue) {
        return isInitialized(config) ? config.get() : defaultValue;
    }
}
