package org.teacon.neb.network.chunk.preshare;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.jetbrains.annotations.Nullable;
import org.teacon.neb.network.aggregate.compress.CompressContext;
import org.teacon.neb.utils.ContextByteBuf;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public final class PresharedChunkBundle {
    private final UUID version;
    private final Long2ObjectMap<PresharedChunk> chunks;

    private PresharedChunkBundle(UUID version, Long2ObjectMap<PresharedChunk> chunks) {
        this.version = version;
        this.chunks = chunks;
    }

    public PresharedChunkBundle(List<PresharedChunk> chunks) {
        this.version = UUID.randomUUID();
        this.chunks = new Long2ObjectLinkedOpenHashMap<>();

        for (PresharedChunk chunk : chunks) {
            if (this.chunks.put(chunk.pos().pack(), chunk) != null) {
                throw new IllegalArgumentException("Duplicate preshared chunk: " + chunk.pos());
            }
        }
    }

    public UUID getVersion() {
        return version;
    }

    @Nullable
    public static PresharedChunk getChunk(PresharedChunkBundle bundle, Level level, ChunkPos pos) {
        if (bundle != null && level.dimension() == Level.OVERWORLD) {
            return bundle.chunks.get(pos.pack());
        }
        return null;
    }

    @Nullable
    public static PresharedChunkBundle load(Path path, RegistryAccess registryAccess) throws IOException {
        if (!Files.exists(path)) {
            return null;
        }

        ByteBuf input = Unpooled.directBuffer();
        try {
            try (InputStream is = Files.newInputStream(path); OutputStream os = new ByteBufOutputStream(input)) {
                is.transferTo(os);
            }

            UUID version = FriendlyByteBuf.readUUID(input);
            Long2ObjectMap<PresharedChunk> chunks = read0(new ContextByteBuf(input, registryAccess, ConnectionType.NEOFORGE));
            return new PresharedChunkBundle(version, chunks);
        } finally {
            input.release();
        }
    }

    public void write(Path path, RegistryAccess registryAccess) throws IOException {
        ContextByteBuf body = new ContextByteBuf(Unpooled.directBuffer(), registryAccess, ConnectionType.NEOFORGE);
        try {
            FriendlyByteBuf.writeUUID(body, version);
            write0(body, chunks);

            try (InputStream is = new ByteBufInputStream(body); OutputStream os = Files.newOutputStream(path)) {
                is.transferTo(os);
            }
        } finally {
            body.release();
        }
    }

    private static Long2ObjectMap<PresharedChunk> read0(ContextByteBuf compressed) {
        ContextByteBuf input;
        CompressContext context = CompressContext.ofPresharedChunk();
        try {
            input = compressed.recreate(context.decompress(compressed));
        } finally {
            context.release();
        }

        try {
            return PresharedChunksIO.read(input);
        } finally {
            input.release();
        }
    }

    private static void write0(ContextByteBuf buffer, Long2ObjectMap<PresharedChunk> value) {
        ContextByteBuf output = buffer.recreate(buffer.alloc().directBuffer());
        try {
            PresharedChunksIO.write(value, output);

            CompressContext context = CompressContext.ofPresharedChunk();
            try {
                context.compress(output, buffer);
            } finally {
                context.release();
            }
        } finally {
            output.release();
        }
    }
}
