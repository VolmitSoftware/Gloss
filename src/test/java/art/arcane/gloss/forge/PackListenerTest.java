package art.arcane.gloss.forge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackListenerTest {
    @TempDir
    Path folder;

    private final PackListener listener = new PackListener("127.0.0.1", 0);

    @AfterEach
    void stop() {
        listener.stop();
    }

    private PackArtifact artifact(String content) throws IOException {
        Path zip = folder.resolve("gloss-pack.zip");
        Files.writeString(zip, content, StandardCharsets.UTF_8);
        byte[] sha1 = new byte[20];
        Arrays.fill(sha1, (byte) content.length());
        StringBuilder hex = new StringBuilder();
        for (byte value : sha1) {
            hex.append(String.format("%02x", value & 0xFF));
        }
        return new PackArtifact(folder.resolve("pack"), zip, sha1, hex.toString(), 0L);
    }

    private int status(String path) throws IOException {
        HttpURLConnection connection = open(path);
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String path) throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + listener.port() + path);
        return (HttpURLConnection) uri.toURL().openConnection();
    }

    @Test
    void servesTheCurrentArtifactOnItsHashedRoute() throws IOException {
        PackArtifact artifact = artifact("pack-bytes");
        listener.start(artifact);

        HttpURLConnection connection = open(artifact.route());
        try (InputStream body = connection.getInputStream()) {
            assertEquals(200, connection.getResponseCode());
            assertEquals("application/zip", connection.getHeaderField("Content-Type"));
            assertArrayEquals(Files.readAllBytes(artifact.zip()), body.readAllBytes());
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void everyOtherPathIsNotFound() throws IOException {
        listener.start(artifact("pack-bytes"));

        assertEquals(404, status("/"));
        assertEquals(404, status("/gloss-pack.zip"));
        assertEquals(404, status("/gloss-pack-deadbeef.zip"));
        assertEquals(404, status("/../../etc/passwd"));
    }

    @Test
    void aRebuildChangesTheRouteAndRetiresTheOldOne() throws IOException {
        PackArtifact first = artifact("one");
        listener.start(first);
        PackArtifact second = artifact("two-bytes");
        listener.publish(second);

        assertEquals(404, status(first.route()));
        assertEquals(200, status(second.route()));
    }

    @Test
    void theUrlNamesTheBoundAddressAndRoute() throws IOException {
        PackArtifact artifact = artifact("pack-bytes");
        listener.start(artifact);

        assertEquals("http://127.0.0.1:" + listener.port() + artifact.route(), listener.url());
        assertTrue(listener.running());
    }

    @Test
    void stoppingReleasesThePort() throws IOException {
        listener.start(artifact("pack-bytes"));
        int port = listener.port();
        listener.stop();

        assertFalse(listener.running());
        assertEquals(0, listener.port());
        PackListener replacement = new PackListener("127.0.0.1", port);
        try {
            assertTrue(replacement.start(artifact("pack-bytes")));
        } finally {
            replacement.stop();
        }
    }
}
