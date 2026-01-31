package org.teacon.neb.network.chunk.preshare.data;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.teacon.neb.utils.ChunkRelativePos;
import org.teacon.neb.utils.ContextByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record BlockEntityInfo(
        ChunkRelativePos pos,
        BlockEntityType<?> type,
        CompoundTag data
) {
    public static final StreamCodec<@NotNull ContextByteBuf, @NotNull Int2ObjectMap<BlockEntityInfo>> BLOCK_CODEC = new StreamCodec<>() {
        @Override
        public Int2ObjectMap<BlockEntityInfo> decode(ContextByteBuf buffer) {
            int size = buffer.readVarInt();
            Int2ObjectMap<BlockEntityInfo> map = new Int2ObjectOpenHashMap<>(size);
            for (int i = 0; i < size; i++) {
                BlockEntityInfo blockEntityInfo = STREAM_CODEC.decode(buffer);
                map.put(blockEntityInfo.pos().pack(), blockEntityInfo);
            }
            return map;
        }

        @Override
        public void encode(ContextByteBuf buffer, Int2ObjectMap<BlockEntityInfo> value) {
            buffer.writeVarInt(value.size());
            for (BlockEntityInfo blockEntityInfo : value.values()) {
                STREAM_CODEC.encode(buffer, blockEntityInfo);
            }
        }
    };

    private static final StreamCodec<@NotNull ContextByteBuf, @NotNull BlockEntityInfo> STREAM_CODEC = StreamCodec.composite(
            ChunkRelativePos.STREAM_CODEC, BlockEntityInfo::pos,
            ByteBufCodecs.registry(BuiltInRegistries.BLOCK_ENTITY_TYPE.key()), BlockEntityInfo::type,
            ByteBufCodecs.COMPOUND_TAG, BlockEntityInfo::data,
            BlockEntityInfo::new
    );

    public static Int2ObjectMap<BlockEntityInfo> createBlockEntitiesCache(LevelChunk chunk) {
        Int2ObjectMap<BlockEntityInfo> blockEntities = new Int2ObjectOpenHashMap<>();

        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            ChunkRelativePos pos = new ChunkRelativePos(entry.getKey(), (byte) 0);
            BlockEntity blockEntity = entry.getValue();

            blockEntities.put(pos.pack(), new BlockEntityInfo(
                    pos, blockEntity.getType(),
                    blockEntity.getUpdateTag(chunk.getLevel().registryAccess())
            ));
        }
        return blockEntities;
    }

    public record Diff(
            ChunkRelativePos pos,
            @Nullable BlockEntityType<?> type,
            @Nullable CompoundTag tag
    ) {
        private static final byte FLAG_NULL_NULL = 0, FLAG_NULL_VALUE = 1, FLAG_VALUE_VALUE = 2;

        public static final StreamCodec<@NotNull RegistryFriendlyByteBuf, @NotNull Diff> STREAM_CODEC = new StreamCodec<>() {
            private static final StreamCodec<@NotNull RegistryFriendlyByteBuf, @NotNull BlockEntityType<?>> BE_TYPE_CODEC =
                    ByteBufCodecs.registry(Registries.BLOCK_ENTITY_TYPE);

            @Override
            public Diff decode(RegistryFriendlyByteBuf buffer) {
                ChunkRelativePos pos = ChunkRelativePos.STREAM_CODEC.decode(buffer);

                BlockEntityType<?> type = null;
                CompoundTag tag = null;
                switch (pos.flag()) {
                    case FLAG_NULL_NULL -> {
                    }
                    case FLAG_NULL_VALUE -> tag = buffer.readNbt();
                    case FLAG_VALUE_VALUE -> {
                        type = BE_TYPE_CODEC.decode(buffer);
                        tag = buffer.readNbt();
                    }
                    default -> throw new IllegalArgumentException("Illegal flag: " + pos.flag());
                }

                return new Diff(pos.withFlag((byte) 0), type, tag);
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, Diff value) {
                byte flag;
                if (value.type == null) {
                    if (value.tag == null) {
                        flag = FLAG_NULL_NULL;
                    } else {
                        flag = FLAG_NULL_VALUE;
                    }
                } else {
                    flag = FLAG_VALUE_VALUE;
                }

                ChunkRelativePos.STREAM_CODEC.encode(buffer, value.pos.withFlag(flag));
                if (value.type != null) {
                    BE_TYPE_CODEC.encode(buffer, value.type);
                }
                if (value.tag != null) {
                    buffer.writeNbt(value.tag);
                }
            }
        };

        public static List<Diff> from(Int2ObjectMap<BlockEntityInfo> base, LevelChunk chunk) {
            List<Diff> blockEntities = new ArrayList<>();
            IntSet existed = new IntOpenHashSet();

            for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                ChunkRelativePos pos = new ChunkRelativePos(entry.getKey(), (byte) 0);
                int posPacked = pos.pack();

                BlockEntity blockEntity = entry.getValue();
                BlockEntityType<?> type = blockEntity.getType();
                CompoundTag tag = blockEntity.getUpdateTag(chunk.getLevel().registryAccess());

                BlockEntityInfo previous = base.get(posPacked);
                if (previous == null || previous.type != type) {
                    blockEntities.add(new Diff(pos, type, tag));
                } else {
                    blockEntities.add(new Diff(pos, null, tag));
                }
                existed.add(posPacked);
            }

            for (Int2ObjectMap.Entry<BlockEntityInfo> entry : base.int2ObjectEntrySet()) {
                int pos = entry.getIntKey();
                if (!existed.contains(pos)) {
                    blockEntities.add(new Diff(ChunkRelativePos.unpack(pos), null, null));
                }
            }

            return blockEntities;
        }

        public static ObjectCollection<BlockEntityInfo> apply(Int2ObjectMap<BlockEntityInfo> base, List<Diff> diffs) {
            Int2ObjectMap<BlockEntityInfo> blockEntities = new Int2ObjectOpenHashMap<>(base);

            for (Diff diff : diffs) {
                int packedPos = diff.pos.pack();

                if (diff.type == null) {
                    if (diff.tag == null) { // Remove
                        Validate.notNull(blockEntities.remove(packedPos), "Cannot remove an inexistent block entity at " + diff.pos);
                    } else { // Update data
                        BlockEntityInfo info = Validate.notNull(blockEntities.get(packedPos), "Cannot update an inexistent block entity at " + diff.pos);
                        blockEntities.put(packedPos, new BlockEntityInfo(info.pos, info.type, diff.tag));
                    }
                } else { // Add
                    blockEntities.put(packedPos, new BlockEntityInfo(diff.pos, diff.type, Objects.requireNonNull(diff.tag)));
                }
            }

            return blockEntities.values();
        }
    }
}
