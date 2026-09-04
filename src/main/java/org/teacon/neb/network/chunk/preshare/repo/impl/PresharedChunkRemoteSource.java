package org.teacon.neb.network.chunk.preshare.repo.impl;

import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;
import org.teacon.neb.NEBConfigs;
import org.teacon.neb.NotEnoughBandwidth;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class PresharedChunkRemoteSource implements IPresharedChunkSource {
    private final String version;
    private final String url;
    private final HttpClient client;

    public PresharedChunkRemoteSource(String version, String url, ProxySelector proxy) {
        this.version = version;
        this.url = url;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .proxy(proxy)
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    @Override
    public @Nullable Path tryLoad(long grid) throws IOException {
        Path file = Files.createTempFile("neb-remote-chunk-", ".bin");

        HttpResponse<Path> response;
        try {
            response = client.send(
                    HttpRequest.newBuilder()
                            .GET()
                            .uri(URI.create(String.format(url, version, ChunkPos.getX(grid), ChunkPos.getZ(grid))))
                            .header("User-Agent", "NotEnoughBandwidth/" + NotEnoughBandwidth.MOD_CONTAINER.getModInfo().getVersion().toString())
                            .timeout(Duration.ofSeconds(NEBConfigs.PRESHARED_CHUNK_REQUEST_TIMEOUT.get()))
                            .build(),
                    HttpResponse.BodyHandlers.ofFile(file)
            );
        } catch (InterruptedException e) {
            throw new IOException(e);
        }

        if (response.statusCode() != 200) {
            throw new IOException("Invalid status: " + response.statusCode());
        }
        return file;
    }

    @Override
    public void close() {
        this.client.close();
    }
}
