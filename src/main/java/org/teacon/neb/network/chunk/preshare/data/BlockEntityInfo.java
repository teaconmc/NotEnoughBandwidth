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
import org.teacon.neb.utils.ChunkRelativePos;
import org.teacon.neb.utils.ContextByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record BlockEntityInfo(
        ChunkRelativePos pos,
        BlockEntityType<?> type,
        CompoundTag data
) {
    public static final StreamCodec<ContextByteBuf, Int2ObjectMap<BlockEntityInfo>> BLOCK_CODEC = new StreamCodec<>() {
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

    private static final StreamCodec<ContextByteBuf, BlockEntityInfo> STREAM_CODEC = StreamCodec.composite(
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
            UpdateOperation operation
    ) {
        private sealed interface UpdateOperation {
            record Remove() implements UpdateOperation {
                private static final Remove INSTANCE = new Remove();
            }

            record Set(BlockEntityType<?> type, CompoundTag tag) implements UpdateOperation {
            }

            record UpdateValue(CompoundTag tag) implements UpdateOperation {
            }

            byte REMOVE = 0, UPDATE = 1, SET = 2;
        }

        public static final StreamCodec<RegistryFriendlyByteBuf, Diff> STREAM_CODEC = new StreamCodec<>() {
            private static final StreamCodec<RegistryFriendlyByteBuf, BlockEntityType<?>> BE_TYPE_CODEC =
                    ByteBufCodecs.registry(Registries.BLOCK_ENTITY_TYPE);

            @Override
            public Diff decode(RegistryFriendlyByteBuf buffer) {
                ChunkRelativePos pos = ChunkRelativePos.STREAM_CODEC.decode(buffer);
                UpdateOperation operation = switch (pos.flag()) {
                    case UpdateOperation.REMOVE -> UpdateOperation.Remove.INSTANCE;
                    case UpdateOperation.UPDATE -> new UpdateOperation.UpdateValue(buffer.readNbt());
                    case UpdateOperation.SET -> {
                        BlockEntityType<?> type = BE_TYPE_CODEC.decode(buffer);
                        CompoundTag tag = buffer.readNbt();
                        yield new UpdateOperation.Set(type, tag);
                    }
                    default -> throw new IllegalArgumentException("Illegal flag: " + pos.flag());
                };

                return new Diff(pos.withFlag((byte) 0), operation);
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, Diff value) {
                byte flag = switch (value.operation) {
                    case UpdateOperation.Remove _ -> UpdateOperation.REMOVE;
                    case UpdateOperation.UpdateValue _ -> UpdateOperation.UPDATE;
                    case UpdateOperation.Set _ -> UpdateOperation.SET;
                };

                ChunkRelativePos.STREAM_CODEC.encode(buffer, value.pos.withFlag(flag));
                switch (value.operation) {
                    case UpdateOperation.Remove _ -> {
                    }
                    case UpdateOperation.UpdateValue(CompoundTag tag) -> buffer.writeNbt(tag);
                    case UpdateOperation.Set(BlockEntityType<?> type, CompoundTag tag) -> {
                        BE_TYPE_CODEC.encode(buffer, type);
                        buffer.writeNbt(tag);
                    }
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
                    blockEntities.add(new Diff(pos, new UpdateOperation.Set(type, tag)));
                } else if (!tag.equals(previous.data)) {
                    blockEntities.add(new Diff(pos, new UpdateOperation.UpdateValue(tag)));
                }

                existed.add(posPacked);
            }

            for (Int2ObjectMap.Entry<BlockEntityInfo> entry : base.int2ObjectEntrySet()) {
                int pos = entry.getIntKey();
                if (!existed.contains(pos)) {
                    blockEntities.add(new Diff(ChunkRelativePos.unpack(pos), UpdateOperation.Remove.INSTANCE));
                }
            }

            return blockEntities;
        }

        public static ObjectCollection<BlockEntityInfo> apply(Int2ObjectMap<BlockEntityInfo> base, List<Diff> diffs) {
            Int2ObjectMap<BlockEntityInfo> blockEntities = new Int2ObjectOpenHashMap<>(base);

            for (Diff diff : diffs) {
                int packedPos = diff.pos.pack();

                switch (diff.operation) {
                    case UpdateOperation.Remove _ -> {
                        Validate.notNull(blockEntities.remove(packedPos), "Cannot remove an inexistent block entity at " + diff.pos);
                    }
                    case UpdateOperation.UpdateValue(CompoundTag tag) -> {
                        BlockEntityInfo info = Validate.notNull(blockEntities.get(packedPos), "Cannot update an inexistent block entity at " + diff.pos);
                        blockEntities.put(packedPos, new BlockEntityInfo(info.pos, info.type, tag));
                    }
                    case UpdateOperation.Set(BlockEntityType<?> type, CompoundTag tag) -> {
                        blockEntities.put(packedPos, new BlockEntityInfo(diff.pos, type, tag));
                    }
                }
            }

            return blockEntities.values();
        }
    }
}
