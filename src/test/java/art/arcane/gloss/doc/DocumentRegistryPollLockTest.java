package art.arcane.gloss.doc;

import art.arcane.volmlib.util.io.FileWatcher;
import art.arcane.volmlib.util.io.FolderWatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hot-reload pass walks and parses off the registry monitor. The server thread reaches a document
 * through the same monitor the watchdog thread holds while it applies, so a pass that also held it
 * across the directory walk and the parses stalled the server for the whole pass — and the full
 * watch scan is not covered by the reconciliation budget.
 */
class DocumentRegistryPollLockTest {
    private static final long PATIENCE_SECONDS = 10L;

    @TempDir
    File folder;
    private final AtomicLong clock = new AtomicLong();

    @Test
    void aReaderDoesNotWaitBehindTheWatcherScan() throws Exception {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = DocumentRegistry.folder("test", folder,
            (fileName, raw) -> raw.trim(), value -> 1L, file -> false, clock::get);
        registry.reload();
        CountDownLatch scanning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        registry.replaceFolderWatcher(new BlockingFolderWatcher(folder, scanning, release));

        Thread poller = poll(registry);
        try {
            assertTrue(scanning.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the pass never scanned");
            assertEquals("one", readValue(registry, "alpha"),
                "a reader must not queue behind the directory walk");
        } finally {
            release.countDown();
            poller.join(TimeUnit.SECONDS.toMillis(PATIENCE_SECONDS));
        }
    }

    @Test
    void aReaderDoesNotWaitBehindAParse() throws Exception {
        File file = new File(folder, "solo.json");
        Files.writeString(file.toPath(), "one", StandardCharsets.UTF_8);
        CountDownLatch parsing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DocumentRegistry<String> registry = DocumentRegistry.singleFile("test", file,
            blockingParser(parsing, release), value -> 1L, clock::get);
        registry.reload();
        registry.replaceFileWatcher(new EventSilentFileWatcher(file));
        Files.writeString(file.toPath(), "two", StandardCharsets.UTF_8);
        clock.set(TimeUnit.SECONDS.toNanos(12L));

        Thread poller = poll(registry);
        try {
            assertTrue(parsing.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the pass never parsed");
            assertEquals("one", readValue(registry, "solo"),
                "a reader must not queue behind the parse of a changed document");
        } finally {
            release.countDown();
            poller.join(TimeUnit.SECONDS.toMillis(PATIENCE_SECONDS));
        }
    }

    @Test
    void anAuthoritativePublishDuringAPassWinsOverTheBytesTheScanRead() throws Exception {
        File file = new File(folder, "solo.json");
        Files.writeString(file.toPath(), "one", StandardCharsets.UTF_8);
        CountDownLatch parsing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DocumentRegistry<String> registry = DocumentRegistry.singleFile("test", file,
            blockingParser(parsing, release), value -> 1L, clock::get);
        registry.reload();
        registry.replaceFileWatcher(new EventSilentFileWatcher(file));
        Files.writeString(file.toPath(), "two", StandardCharsets.UTF_8);
        clock.set(TimeUnit.SECONDS.toNanos(12L));

        AtomicReference<DocumentDelta> delta = new AtomicReference<>(DocumentDelta.EMPTY);
        Thread poller = new Thread(() -> delta.set(registry.poll()), "poll-lock-test");
        poller.setDaemon(true);
        poller.start();
        assertTrue(parsing.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the pass never parsed");
        registry.publish("solo", "three", "three");
        release.countDown();
        poller.join(TimeUnit.SECONDS.toMillis(PATIENCE_SECONDS));

        assertEquals("three", registry.get("solo").value(),
            "the owner's publish is authoritative over bytes the pass read before it");
        assertTrue(delta.get().isEmpty(), "the stale read must not be staged as a change");
    }

    private static DocumentParser<String> blockingParser(CountDownLatch parsing, CountDownLatch release) {
        return (fileName, raw) -> {
            if (raw.trim().equals("two")) {
                parsing.countDown();
                try {
                    release.await(PATIENCE_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return raw.trim();
        };
    }

    private static String readValue(DocumentRegistry<String> registry, String id)
        throws InterruptedException {
        AtomicReference<GlossDocument<String>> document = new AtomicReference<>();
        CountDownLatch read = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            document.set(registry.get(DocumentDelta.EMPTY, id));
            read.countDown();
        }, "poll-lock-reader");
        reader.setDaemon(true);
        reader.start();
        assertTrue(read.await(PATIENCE_SECONDS, TimeUnit.SECONDS),
            "the reader is still waiting on the registry monitor");
        assertNotNull(document.get());
        return document.get().value();
    }

    private static Thread poll(DocumentRegistry<String> registry) {
        Thread poller = new Thread(registry::poll, "poll-lock-test");
        poller.setDaemon(true);
        poller.start();
        return poller;
    }

    private void write(String name, String content) throws IOException {
        File file = new File(folder, name);
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        assertTrue(file.setLastModified(file.lastModified() + 5000L));
    }

    /** A watcher whose scan blocks until the test releases it, standing in for a slow disk walk. */
    private static final class BlockingFolderWatcher extends FolderWatcher {
        private final CountDownLatch scanning;
        private final CountDownLatch release;

        private BlockingFolderWatcher(File folder, CountDownLatch scanning, CountDownLatch release) {
            super(folder);
            this.scanning = scanning;
            this.release = release;
        }

        @Override
        public boolean checkModified() {
            return block();
        }

        @Override
        public boolean checkModifiedEvents() {
            return block();
        }

        private boolean block() {
            scanning.countDown();
            try {
                release.await(PATIENCE_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static final class EventSilentFileWatcher extends FileWatcher {
        private EventSilentFileWatcher(File file) {
            super(file);
        }

        @Override
        public boolean checkModifiedEvents() {
            return false;
        }
    }
}
