package org.teacon.neb.network.chunk.preshare;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.NotNull;
import org.teacon.neb.NotEnoughBandwidth;
import org.teacon.neb.network.chunk.preshare.data.BlockEntityInfo;
import org.teacon.neb.network.chunk.preshare.data.HeightMap;
import org.teacon.neb.network.chunk.preshare.data.LevelLightSection;
import org.teacon.neb.network.chunk.preshare.data.SectionInstance;

import java.util.List;

@EventBusSubscriber
public record PresharedChunkPacket(
        ChunkPos pos,
        HeightMap.Diff heightmaps,
        List<SectionInstance.Diff> sections,
        List<LevelLightSection.Diff> lights,
        List<BlockEntityInfo.Diff> blockEntities
) implements CustomPacketPayload {
    public static final Type<PresharedChunkPacket> TYPE = new Type<>(NotEnoughBandwidth.id("s2c/preshared_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PresharedChunkPacket> STREAM_CODEC = new StreamCodec<>() {
        public static final StreamCodec<RegistryFriendlyByteBuf, PresharedChunkPacket> DELEGATE = StreamCodec.composite(
                ChunkPos.STREAM_CODEC, PresharedChunkPacket::pos,
                HeightMap.Diff.STREAM_CODEC, PresharedChunkPacket::heightmaps,
                SectionInstance.Diff.STREAM_CODEC.apply(ByteBufCodecs.list()), PresharedChunkPacket::sections,
                LevelLightSection.Diff.STREAM_CODEC.apply(ByteBufCodecs.list()), PresharedChunkPacket::lights,
                BlockEntityInfo.Diff.STREAM_CODEC.apply(ByteBufCodecs.list()), PresharedChunkPacket::blockEntities,
                PresharedChunkPacket::new
        );

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, PresharedChunkPacket value) {
            ByteBuf source = buffer.alloc().directBuffer(4096, Integer.MAX_VALUE);
            try {
                DELEGATE.encode(new RegistryFriendlyByteBuf(source, buffer.registryAccess(), buffer.getConnectionType()), value);

                int limit = source.writerIndex();
                buffer.writeVarInt(limit);

                for (int i = 0; i < limit; ) {
                    int end;
                    if (source.getByte(i) == 0) {
                        end = findNext(i + 1, source, false);
                        buffer.writeByte(0);
                        buffer.writeVarInt(end - i);
                    } else {
                        end = findNext(i + 1, source, true);
                        buffer.writeBytes(source, i, end - i);
                    }
                    i = end;
                }
            } finally {
                source.release();
            }
        }

        private int findNext(int i, ByteBuf buffer, boolean isZero) {
            for (int limit = buffer.writerIndex(); i < limit; i++) {
                if (isZero == (buffer.getByte(i) == 0)) {
                    break;
                }
            }
            return i;
        }

        @Override
        public PresharedChunkPacket decode(RegistryFriendlyByteBuf buffer) {
            ByteBuf target = buffer.alloc().directBuffer(buffer.readVarInt());
            try {
                while (buffer.readableBytes() > 0) {
                    byte byteValue = buffer.readByte();
                    if (byteValue == 0) {
                        target.writeZero(buffer.readVarInt());
                    } else {
                        target.writeByte(byteValue);
                    }
                }

                return DELEGATE.decode(new RegistryFriendlyByteBuf(target, buffer.registryAccess(), buffer.getConnectionType()));
            } finally {
                target.release();
            }
        }
    };

    @Override
    @NotNull
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    @SubscribeEvent
    private static void on(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NotEnoughBandwidth.MOD_CONTAINER.getModInfo().getVersion().toString());
        registrar.playToClient(TYPE, STREAM_CODEC);
    }
}
