package art.arcane.gloss.forge;

import art.arcane.gloss.GlossConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackArtifactStoreTest {
    @TempDir Path folder;
    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
    private final PackArtifactStore store = new PackArtifactStore(clock::get);
    private final GlossConfig.PackLimits limits = new GlossConfig.PackLimits(100, 100000, 10000, 2, 1000, 10);

    private PackArtifact artifact(String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] hash = MessageDigest.getInstance("SHA-1").digest(bytes);
        String hex = HexFormat.of().formatHex(hash);
        return new PackArtifact(folder.resolve("pack"), folder.resolve("artifacts/" + hex + ".zip"),
            hash, hex, clock.get());
    }

    private void install(PackArtifact artifact, String content) throws IOException {
        Path staged = folder.resolve("staged.zip");
        Files.writeString(staged, content);
        try (PackArtifactStore.Lease lease = store.install(artifact, staged, limits)) {
            store.published(artifact);
        }
    }

    @Test
    void downloadAndOfferLeasesPreventPruningUntilBothReleaseAndGraceExpires() throws Exception {
        PackArtifact first = artifact("first");
        install(first, "first");
        PackArtifactStore.Lease offer = store.retain(first);
        PackArtifactStore.Lease download = store.acquireRoute(first.route());
        assertNotNull(download);
        PackArtifact second = artifact("second");
        install(second, "second");
        clock.addAndGet(20000);
        PackArtifact third = artifact("third");

        assertThrows(IOException.class, () -> install(third, "third"));
        offer.close();
        offer.close();
        clock.addAndGet(20000);
        assertThrows(IOException.class, () -> install(third, "third"));
        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(first.zip()));
        download.close();
        assertThrows(IOException.class, () -> install(third, "third"));
        clock.addAndGet(10001);
        install(third, "third");

        assertFalse(Files.exists(first.zip()));
        assertTrue(Files.exists(second.zip()));
        assertTrue(Files.exists(third.zip()));
    }

    @Test
    void currentArtifactCannotBePrunedToFitAnOversizedReplacement() throws Exception {
        PackArtifact first = artifact("first");
        install(first, "first");
        clock.addAndGet(20000);
        PackArtifact next = artifact("x".repeat(1000));

        assertThrows(IOException.class, () -> install(next, "x".repeat(1000)));

        assertTrue(Files.exists(first.zip()));
        assertFalse(Files.exists(next.zip()));
    }

    @Test
    void aRestartProtectsThePublishedHashAndGivesOldUrlsANewGracePeriod() throws Exception {
        PackArtifact first = artifact("first");
        install(first, "first");
        PackArtifact second = artifact("second");
        install(second, "second");
        Files.writeString(folder.resolve(PackBuilder.SHA1_NAME), second.sha1Hex() + "\n");
        clock.addAndGet(20000);
        PackArtifactStore restarted = new PackArtifactStore(clock::get);
        PackArtifact next = artifact("third");
        Path staged = folder.resolve("third.zip");
        Files.writeString(staged, "third");

        assertThrows(IOException.class, () -> restarted.install(next, staged, limits));
        try (PackArtifactStore.Lease old = restarted.acquireRoute(first.route())) {
            assertNotNull(old);
        }
        clock.addAndGet(10001);
        try (PackArtifactStore.Lease accepted = restarted.install(next, staged, limits)) {
            assertNotNull(accepted);
        }

        assertFalse(Files.exists(first.zip()));
        assertTrue(Files.exists(second.zip()));
    }

    @Test
    void anExistingHashWithDifferentBytesIsNeverReplaced() throws Exception {
        PackArtifact first = artifact("first");
        install(first, "first");
        Files.writeString(first.zip(), "tampered");

        assertThrows(IOException.class, () -> install(first, "first"));

        assertArrayEquals("tampered".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(first.zip()));
    }

    @Test
    void anArtifactRetainedBeforeInventoryStillCountsItsActualDiskBytes() throws Exception {
        PackArtifact first = artifact("x".repeat(900));
        Files.createDirectories(first.zip().getParent());
        Files.writeString(first.zip(), "x".repeat(900));
        PackArtifact next = artifact("y".repeat(200));
        try (PackArtifactStore.Lease retained = store.retain(first)) {
            assertThrows(IOException.class, () -> install(next, "y".repeat(200)));
            assertTrue(Files.exists(first.zip()));
            assertFalse(Files.exists(next.zip()));
        }
    }

    @Test
    void externallyGrownArtifactsCannotEvadeTheStorageByteLimit() throws Exception {
        PackArtifact first = artifact("first");
        install(first, "first");
        Files.writeString(first.zip(), "x".repeat(900));
        PackArtifact next = artifact("y".repeat(200));

        assertThrows(IOException.class, () -> install(next, "y".repeat(200)));

        assertTrue(Files.exists(first.zip()));
        assertFalse(Files.exists(next.zip()));
    }

    @Test
    void raisingRetentionExtendsTheGraceForAlreadyRetiredArtifacts() throws Exception {
        PackArtifact first = artifact("first");
        install(first, "first");
        PackArtifact second = artifact("second");
        install(second, "second");
        clock.addAndGet(20000);
        GlossConfig.PackLimits extended = new GlossConfig.PackLimits(100, 100000, 10000, 2, 1000, 100);
        PackArtifact third = artifact("third");
        Path staged = folder.resolve("staged.zip");
        Files.writeString(staged, "third");

        assertThrows(IOException.class, () -> store.install(third, staged, extended));

        assertTrue(Files.exists(first.zip()));
        assertTrue(Files.exists(second.zip()));
        clock.addAndGet(80001);
        try (PackArtifactStore.Lease accepted = store.install(third, staged, extended)) {
            assertNotNull(accepted);
        }
        assertFalse(Files.exists(first.zip()));
        assertTrue(Files.exists(second.zip()));
    }

    @Test
    void retirementRefusesNewLeasesWithoutHoldingTheStateLockDuringDeletion() throws Exception {
        CountDownLatch deleting = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        PackArtifactStore concurrent = new PackArtifactStore(clock::get, artifact -> {
            deleting.countDown();
            try {
                if (!proceed.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to finish retirement");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException(failure);
            }
        });
        PackArtifact first = artifact("first");
        PackArtifact second = artifact("second");
        Path staged = folder.resolve("staged.zip");
        Files.writeString(staged, "first");
        try (PackArtifactStore.Lease lease = concurrent.install(first, staged, limits)) {
            concurrent.published(first);
        }
        Files.writeString(staged, "second");
        try (PackArtifactStore.Lease lease = concurrent.install(second, staged, limits)) {
            concurrent.published(second);
        }
        clock.addAndGet(20000);
        PackArtifact third = artifact("third");
        Files.writeString(staged, "third");
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> rebuild = executor.submit(() -> {
                try (PackArtifactStore.Lease lease = concurrent.install(third, staged, limits)) {
                    return lease.artifact();
                }
            });
            try {
                assertTrue(deleting.await(5, TimeUnit.SECONDS));
                Future<?> refused = executor.submit(() ->
                    assertThrows(IllegalStateException.class, () -> concurrent.retain(first)));
                refused.get(1, TimeUnit.SECONDS);
                try (PackArtifactStore.Lease current = concurrent.retain(second)) {
                    assertNotNull(current);
                }
            } finally {
                proceed.countDown();
            }
            rebuild.get(5, TimeUnit.SECONDS);
        }
        assertFalse(Files.exists(first.zip()));
        assertTrue(Files.exists(second.zip()));
    }

    @Test
    void aFailedDeletionRestoresEveryUnprocessedArtifactToTheInventory() throws Exception {
        GlossConfig.PackLimits roomy = new GlossConfig.PackLimits(100, 100000, 10000, 4, 1000, 10);
        List<PackArtifact> old = List.of(artifact("first"), artifact("second"), artifact("third"));
        Path staged = folder.resolve("staged.zip");
        for (PackArtifact artifact : old) {
            Files.writeString(staged, "pack");
            try (PackArtifactStore.Lease lease = store.install(artifact, staged, roomy)) {
                store.published(artifact);
            }
        }
        PackArtifact current = artifact("current");
        Files.writeString(staged, "current");
        try (PackArtifactStore.Lease lease = store.install(current, staged, roomy)) {
            store.published(current);
        }
        for (PackArtifact artifact : old) {
            Files.delete(artifact.zip());
            Files.createDirectory(artifact.zip());
            Files.writeString(artifact.zip().resolve("preserve"), "external");
        }
        clock.addAndGet(20000);
        PackArtifact next = artifact("next");
        Files.writeString(staged, "next");

        assertThrows(IOException.class, () -> store.install(next, staged, roomy));

        for (PackArtifact artifact : old) {
            try (PackArtifactStore.Lease lease = store.acquireRoute(artifact.route())) {
                assertNotNull(lease);
            }
            assertTrue(Files.exists(artifact.zip().resolve("preserve")));
        }
        assertTrue(Files.exists(current.zip()));
    }
}
