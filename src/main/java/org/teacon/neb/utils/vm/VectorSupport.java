/*
 * VectorSupport

 * Copyright (c) 2025 Burning_TNT<pangyl08@163.com>

 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:

 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.

 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.teacon.neb.utils.vm;

import com.mojang.logging.LogUtils;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Objects;

public final class VectorSupport {
    private VectorSupport() {
    }

    private static final MethodHandle XOR_J, XOR_Z, NON_EMPTY_B;

    static {
        try {
            Context context = Context.create();

            XOR_J = context.resolve("xor", MethodType.methodType(void.class, long[].class, int.class, long[].class, int.class, long[].class, int.class, int.class));
            XOR_Z = context.resolve("xor", MethodType.methodType(void.class, byte[].class, int.class, byte[].class, int.class, byte[].class, int.class, int.class));
            NON_EMPTY_B = context.resolve("isEmpty", MethodType.methodType(boolean.class, byte[].class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static void xor(long[] array1, int index1, long[] array2, int index2, long[] out, int index3, int length) {
        Objects.checkFromIndexSize(index1, length, array1.length);
        Objects.checkFromIndexSize(index2, length, array2.length);
        Objects.checkFromIndexSize(index3, length, out.length);

        try {
            XOR_J.invokeExact(array1, index1, array2, index2, out, index3, length);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static void xor(byte[] array1, int index1, byte[] array2, int index2, byte[] out, int index3, int length) {
        Objects.checkFromIndexSize(index1, length, array1.length);
        Objects.checkFromIndexSize(index2, length, array2.length);
        Objects.checkFromIndexSize(index3, length, out.length);

        try {
            XOR_Z.invokeExact(array1, index1, array2, index2, out, index3, length);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isEmpty(byte[] value) {
        try {
            return (boolean) NON_EMPTY_B.invokeExact(value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private record Context(
            Class<?> fallback, @Nullable Class<?> vectorized
    ) {
        private static final Logger LOGGER = LogUtils.getLogger();

        public static Context create() throws ReflectiveOperationException {
            Class<?> fallback = Class.forName(VectorSupport.class.getName() + "$Fallback");

            Class<?> vectorized = null;
            try {
                try {
                    Class.forName("jdk.incubator.vector.Vector");
                } catch (ClassNotFoundException _) {
                    MethodHandle loadModule = LookupAccess.IMPL_LOOKUP.findStatic(
                            Class.forName("jdk.internal.module.Modules"),
                            "loadModule",
                            MethodType.methodType(Module.class, String.class)
                    );
                    Module _ = (Module) loadModule.invokeExact("jdk.incubator.vector");
                }

                vectorized = Class.forName(VectorSupport.class.getName() + "$Vectorized");
                LOGGER.warn("Using incubating Vector API to accelerate path calculation.");
            } catch (Throwable e) {
                LOGGER.warn("Cannot accelerate patch calculation: No Vector API available.", e);
            }

            return new Context(fallback, vectorized);
        }

        public MethodHandle resolve(String name, MethodType type) throws ReflectiveOperationException {
            MethodHandle fallbackMH = LookupAccess.IMPL_LOOKUP.findStatic(fallback, name, type);
            if (vectorized != null) {
                MethodHandle vectorizedMH = LookupAccess.IMPL_LOOKUP.findStatic(vectorized, name, type);
                if (!fallbackMH.type().equals(vectorizedMH.type())) {
                    throw new IllegalAccessException("Illegal Vector API declaration: " + name + type);
                }
                return vectorizedMH;
            }
            return fallbackMH;
        }
    }

    private static final class Vectorized {
        private static final VectorSpecies<Long> LONG_SPECIES = LongVector.SPECIES_PREFERRED;

        public static void xor(long[] array1, int index1, long[] array2, int index2, long[] out, int index3, int length) {
            int i = 0;
            for (int bound = LONG_SPECIES.loopBound(length); i < bound; i += LONG_SPECIES.length()) {
                LongVector a = LongVector.fromArray(LONG_SPECIES, array1, index1 + i);
                LongVector b = LongVector.fromArray(LONG_SPECIES, array2, index2 + i);
                a.lanewise(VectorOperators.XOR, b).intoArray(out, index3 + i);
            }
            for (; i < length; i++) {
                out[index3 + i] = array1[index1 + i] ^ array2[index2 + i];
            }
        }

        private static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_PREFERRED;

        public static void xor(byte[] array1, int index1, byte[] array2, int index2, byte[] out, int index3, int length) {
            int i = 0;
            for (int bound = BYTE_SPECIES.loopBound(length); i < bound; i += BYTE_SPECIES.length()) {
                ByteVector a = ByteVector.fromArray(BYTE_SPECIES, array1, index1 + i);
                ByteVector b = ByteVector.fromArray(BYTE_SPECIES, array2, index2 + i);
                a.lanewise(VectorOperators.XOR, b).intoArray(out, index3 + i);
            }
            for (; i < length; i++) {
                out[index3 + i] = (byte) (array1[index1 + i] ^ array2[index2 + i]);
            }
        }

        public static boolean isEmpty(byte[] value) {
            int i = 0;
            for (int bound = BYTE_SPECIES.loopBound(value.length); i < bound; i += BYTE_SPECIES.length()) {
                ByteVector v = ByteVector.fromArray(BYTE_SPECIES, value, i);
                if (!v.eq((byte) 0).allTrue()) {
                    return false;
                }
            }
            for (; i < value.length; i++) {
                if (value[i] != 0) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class Fallback {
        public static void xor(long[] array1, int index1, long[] array2, int index2, long[] out, int index3, int length) {
            for (int i = 0; i < length; i++) {
                out[index3 + i] = array1[index1 + i] ^ array2[index2 + i];
            }
        }

        private static final VarHandle B_J = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());

        public static void xor(byte[] array1, int index1, byte[] array2, int index2, byte[] out, int index3, int length) {
            int i = 0;
            if ((index1 & 7) == 0 && (index2 & 7) == 0 && (index3 & 7) == 0) {
                // Fast path for aligned access.
                for (int bound = length & 7; i < bound; i++) {
                    long v1 = (long) B_J.get(array1, index1 + i);
                    long v2 = (long) B_J.get(array2, index2 + i);

                    B_J.set(out, index3 + i, v1 ^ v2);
                }
            }
            for (; i < length; i++) {
                out[index3 + i] = (byte) (array1[index1 + i] ^ array2[index2 + i]);
            }
        }

        public static boolean isEmpty(byte[] value) {
            int i = 0;
            for (int bound = value.length & ~7; i < bound; i += 8) {
                long v = (long) B_J.get(value, i);
                if (v != 0) {
                    return false;
                }
            }
            for (; i < value.length; i++) {
                if (value[i] != 0) {
                    return false;
                }
            }
            return true;
        }
    }
}
