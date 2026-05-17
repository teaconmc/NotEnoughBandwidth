package org.teacon.neb.network.chunk.preshare.repo.impl;

import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;

@NullMarked
public final class PresharedChunkLocalSource implements IPresharedChunkSource {
    private final Path directory;

    public PresharedChunkLocalSource(Path directory) {
        this.directory = directory;
    }

    @Nullable
    @Override
    public Path tryLoad(long grid) {
        Path path = directory.resolve(getName(grid));
        return Files.isReadable(path) ? path : null;
    }

    public static String getName(long grid) {
        return ChunkPos.getX(grid) + "," + ChunkPos.getZ(grid) + ".grid.neb";
    }

    public static Path resolveIndex(Path directory) {
        return directory.resolve("index");
    }
}
