package org.teacon.neb.network.chunk.preshare.data;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.neoforged.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.Nullable;
import org.teacon.neb.utils.ScopedArrayAllocator;
import org.teacon.neb.utils.vm.VectorSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public record LevelLightSection(
        byte[] block, byte[] sky
) {
    private static final byte[][] BYTES_2048 = new byte[16][2048];

    static {
        for (int i = 1; i < 16; i++) {
            Arrays.fill(BYTES_2048[i], (byte) ((i << 4) | i));
        }
    }

    private static byte[] acquireBytes2048(int i) {
        byte[] array = BYTES_2048[i];
        if (!FMLEnvironment.isProduction()) {
            byte value = (byte) ((i << 4) | i);
            for (byte b : array) {
                if (b != value) {
                    throw new AssertionError("Cannot reuse BYTES_2048: it has been altered!");
                }
            }
        }
        return array;
    }

    private static final StreamCodec<ByteBuf, byte[]> CODEC_BYTES_2048 = new StreamCodec<>() {
        @Override
        public byte[] decode(ByteBuf input) {
            byte status = input.readByte();
            if ((status & 0x01) != 0) {
                return acquireBytes2048((status >> 4) & 0b1111);
            }

            byte[] bytes = new byte[2048];
            input.readBytes(bytes);
            return bytes;
        }

        @Override
        public void encode(ByteBuf buffer, byte[] value) {
            if (value.length != 2048) {
                throw new AssertionError("length must be 2048, not " + value.length);
            }

            byte b = value[0];
            if ((b >> 4) != (b & 0b1111)) {
                buffer.writeByte(1 | (b << 4));
                return;
            }
            for (int i = 1; i < value.length; i++) {
                if (b != value[i]) {
                    buffer.writeByte(0);
                    buffer.writeBytes(value);
                    return;
                }
            }
            buffer.writeByte(1 | (b << 4));
        }
    };

    public static final StreamCodec<ByteBuf, List<LevelLightSection>> STREAM_CODEC = StreamCodec.composite(
            CODEC_BYTES_2048, LevelLightSection::block,
            CODEC_BYTES_2048, LevelLightSection::sky,
            LevelLightSection::new
    ).apply(ByteBufCodecs.list());

    public static List<LevelLightSection> create(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        LevelLightEngine lightEngine = chunk.getLevel().getLightEngine();

        int lightSectionCount = lightEngine.getLightSectionCount();
        List<LevelLightSection> lights = new ArrayList<>(lightSectionCount);
        for (int i = 0; i < lightSectionCount; i++) {
            SectionPos sectionPos = SectionPos.of(pos, i + lightEngine.getMinLightSection());
            lights.add(new LevelLightSection(
                    createDataLayer(lightEngine, sectionPos, LightLayer.BLOCK),
                    createDataLayer(lightEngine, sectionPos, LightLayer.SKY)
            ));
        }

        return lights;
    }

    private static byte @Nullable [] createDataLayer(LevelLightEngine lightEngine, SectionPos pos, LightLayer type) {
        DataLayer data = lightEngine.getLayerListener(type).getDataLayerData(pos);
        if (data == null) {
            return acquireBytes2048(0);
        }

        byte[] val = data.getData();
        if (val.length != 2048) {
            throw new AssertionError(String.format("LightLayer should be 2048 bytes, but found %d bytes.", val.length));
        }
        return val;
    }

    public record Diff(
            byte[] block, byte[] sky
    ) {
        public static final StreamCodec<FriendlyByteBuf, Diff> STREAM_CODEC = StreamCodec.composite(
                CODEC_BYTES_2048, Diff::block,
                CODEC_BYTES_2048, Diff::sky,
                Diff::new
        );

        public static List<Diff> from(List<LevelLightSection> bases, LevelChunk chunk) {
            ChunkPos pos = chunk.getPos();
            LevelLightEngine lightEngine = chunk.getLevel().getLightEngine();

            int lightSectionCount = lightEngine.getLightSectionCount();
            if (bases.size() != lightSectionCount) {
                throw new AssertionError(String.format("Invalid base, expecting %d LevelLightSections, but found %d.", lightSectionCount, bases.size()));
            }

            List<Diff> lights = new ArrayList<>(lightSectionCount);
            for (int i = 0; i < lightSectionCount; i++) {
                SectionPos sectionPos = SectionPos.of(pos, i + lightEngine.getMinLightSection());
                LevelLightSection base = bases.get(i);

                lights.add(new Diff(
                        diff(createDataLayer(lightEngine, sectionPos, LightLayer.BLOCK), base.block),
                        diff(createDataLayer(lightEngine, sectionPos, LightLayer.SKY), base.sky)
                ));
            }

            return lights;
        }

        private static byte[] diff(byte[] left, byte[] right) {
            byte[] bytes = ScopedArrayAllocator.allocateUninitialized(byte[].class, 2048);

            if (left == BYTES_2048[0]) {
                System.arraycopy(right, 0, bytes, 0, 2048);
            } else {
                VectorSupport.xor(left, 0, right, 0, bytes, 0, 2048);
            }
            return bytes;
        }
    }
}
