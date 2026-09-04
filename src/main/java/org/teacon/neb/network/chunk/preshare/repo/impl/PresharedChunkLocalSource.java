package org.teacon.neb.network.chunk.preshare.repo.impl;

import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

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

    @Override
    public void tryCache(long grid, Path result) throws IOException {
        String name = getName(grid);
        Path path = directory.resolve(name);
        if (Files.isRegularFile(path)) {
            return;
        }

        Path temp = directory.resolve(UUID.randomUUID() + ".tmp");
        Files.createDirectories(path.getParent());
        Files.copy(result, temp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE);
    }

    public static String getName(long grid) {
        return ChunkPos.getX(grid) + "," + ChunkPos.getZ(grid) + ".grid.neb";
    }

    public static Path resolveIndex(Path directory) {
        return directory.resolve("index");
    }
}
