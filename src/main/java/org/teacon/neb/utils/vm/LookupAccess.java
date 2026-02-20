package org.teacon.neb.utils.vm;

import com.google.common.collect.ImmutableMap;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class LookupAccess {
    private LookupAccess() {
    }

    public static final MethodHandles.Lookup IMPL_LOOKUP;
    private static final MethodHandle ALLOCATE_INSTANCE;

    static {
        try {
            IMPL_LOOKUP = acquireTrustedLookup();

            Class<?> unsafe = Class.forName("jdk.internal.misc.Unsafe");
            Object theUnsafe = LookupAccess.IMPL_LOOKUP.findStaticVarHandle(unsafe, "theUnsafe", unsafe).get();
            MethodHandle allocateInstance = LookupAccess.IMPL_LOOKUP.findVirtual(unsafe, "allocateInstance", MethodType.methodType(Object.class, Class.class));
            ALLOCATE_INSTANCE = allocateInstance.bindTo(theUnsafe);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("removal")
    private static MethodHandles.Lookup acquireTrustedLookup() throws ReflectiveOperationException, LinkageError {
        Field sunUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        sunUnsafe.setAccessible(true);
        Unsafe theSunUnsafe = (Unsafe) sunUnsafe.get(null);

        Field implLookup = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
        return (MethodHandles.Lookup) theSunUnsafe.getObject(theSunUnsafe.staticFieldBase(implLookup), theSunUnsafe.staticFieldOffset(implLookup));
    }

    public static MethodHandle createConstructor(Class<?> clazz, ImmutableMap<String, Class<?>> fields) throws ReflectiveOperationException {
        List<MethodHandle> setters = new ArrayList<>(fields.size());
        for (Map.Entry<String, Class<?>> entry : fields.entrySet()) {
            setters.add(IMPL_LOOKUP.findSetter(clazz, entry.getKey(), entry.getValue()));
        }

        MethodHandle transmuted = MethodHandles.identity(clazz);
        for (int i = setters.size() - 1; i >= 0; i--) {
            MethodHandle setter = setters.get(i);

            transmuted = MethodHandles.dropArguments(transmuted, 1, setter.type().parameterType(1));
            transmuted = MethodHandles.foldArguments(transmuted, setter);
        }

        MethodHandle allocate = MethodHandles.explicitCastArguments(ALLOCATE_INSTANCE.bindTo(clazz), MethodType.methodType(clazz));
        return MethodHandles.foldArguments(transmuted, allocate);
    }

    public static RuntimeException raise(Throwable t) {
        return switch (t) {
            case RuntimeException re -> re;
            case Error e -> throw e;
            default -> new RuntimeException(t);
        };
    }
}
