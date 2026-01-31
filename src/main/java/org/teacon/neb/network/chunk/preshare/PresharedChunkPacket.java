package org.teacon.neb.network.chunk.preshare;

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

@EventBusSubscriber(modid = NotEnoughBandwidth.MODID)
public record PresharedChunkPacket(
        ChunkPos pos,
        HeightMap.Diff heightmaps,
        List<SectionInstance.Diff> sections,
        List<LevelLightSection> lights,
        List<BlockEntityInfo.Diff> blockEntities
) implements CustomPacketPayload {
    public static final Type<@NotNull PresharedChunkPacket> TYPE = new Type<>(NotEnoughBandwidth.id("s2c/preshared_chunk"));

    public static final StreamCodec<@NotNull RegistryFriendlyByteBuf, @NotNull PresharedChunkPacket> STREAM_CODEC = StreamCodec.composite(
            ChunkPos.STREAM_CODEC, PresharedChunkPacket::pos,
            HeightMap.Diff.STREAM_CODEC, PresharedChunkPacket::heightmaps,
            SectionInstance.Diff.STREAM_CODEC.apply(ByteBufCodecs.list()), PresharedChunkPacket::sections,
            LevelLightSection.STREAM_CODEC.apply(ByteBufCodecs.list()), PresharedChunkPacket::lights,
            BlockEntityInfo.Diff.STREAM_CODEC.apply(ByteBufCodecs.list()), PresharedChunkPacket::blockEntities,
            PresharedChunkPacket::new
    );

    @Override
    @NotNull
    public Type<? extends @NotNull CustomPacketPayload> type() {
        return TYPE;
    }

    @SubscribeEvent
    private static void on(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NotEnoughBandwidth.MOD_CONTAINER.getModInfo().getVersion().toString());
        registrar.playToClient(TYPE, STREAM_CODEC);
    }
}
