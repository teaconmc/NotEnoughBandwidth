package org.teacon.neb.network.chunk.preshare.repo.impl;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

@NullMarked
public interface IPresharedChunkSource {
    @Nullable
    Path tryLoad(long grid) throws IOException;

    default void close() throws IOException {
    }
}
