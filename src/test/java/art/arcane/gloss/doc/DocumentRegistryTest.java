package art.arcane.gloss.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentRegistryTest {
    private static final DocumentParser<String> PARSER = (fileName, raw) -> {
        String value = raw.trim();
        if (value.contains("bad")) {
            throw new IllegalArgumentException(fileName + " is broken");
        }
        return value;
    };

    @TempDir
    File folder;
    private final AtomicLong clock = new AtomicLong();

    private DocumentRegistry<String> registry() {
        return DocumentRegistry.folder("test", folder, PARSER, value -> 1L, file -> false, clock::get);
    }

    private void write(String name, String content) throws IOException {
        File file = new File(folder, name);
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        assertTrue(file.setLastModified(file.lastModified() + 5000L));
    }

    @Test
    void reloadLoadsEveryDocumentAndPublishesSnapshot() throws IOException {
        write("alpha.json", "one");
        write("beta.json", "two");
        write("upload.tmp.json", "temporary");
        write(".alpha.json", "temporary");
        write("notes.txt", "ignored");
        DocumentRegistry<String> registry = registry();

        registry.reload();

        assertEquals(Set.of("alpha", "beta"), registry.ids());
        assertEquals("one", registry.get("alpha").value());
        assertEquals("two", registry.get("beta").value());
        assertEquals(DocumentHashes.sha256("one"), registry.get("alpha").contentHash());
        assertEquals(1L, registry.get("alpha").revision());
    }

    @Test
    void reloadDropsDocumentsWhoseFilesAreGone() throws IOException {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();

        assertTrue(new File(folder, "alpha.json").delete());
        registry.reload();

        assertEquals(Set.of(), registry.ids());
        assertNull(registry.get("alpha"));
    }

    @Test
    void parseFailureOnReloadKeepsTheLastGoodDocument() throws IOException {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();

        write("alpha.json", "bad content");
        registry.reload();

        assertEquals("one", registry.get("alpha").value());
    }

    @Test
    void pollReportsChangedCreatedAndDeletedDocuments() throws IOException {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();

        write("alpha.json", "one edited");
        write("beta.json", "two");
        DocumentDelta first = registry.poll();
        assertEquals(Set.of("alpha", "beta"), Set.copyOf(first.loaded()));
        assertEquals("one", registry.get("alpha").value());
        assertNull(registry.get("beta"));
        assertEquals("one edited", registry.get(first, "alpha").value());
        assertEquals("two", registry.get(first, "beta").value());
        assertTrue(registry.acknowledge(first));
        assertEquals("one edited", registry.get("alpha").value());
        assertEquals("two", registry.get("beta").value());

        assertTrue(new File(folder, "beta.json").delete());
        assertTrue(registry.poll().isEmpty());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3L));
        DocumentDelta second = registry.poll();
        assertEquals(Set.of("beta"), Set.copyOf(second.removed()));
        assertEquals("two", registry.get("beta").value());
        assertNull(registry.get(second, "beta"));
        assertTrue(registry.acknowledge(second));
        assertNull(registry.get("beta"));
    }

    @Test
    void pollWithoutChangesIsEmpty() throws IOException {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();

        assertTrue(registry.poll().isEmpty());
    }

    @Test
    void pollParseFailureKeepsLastGoodAndReportsNothing() throws IOException {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();

        write("alpha.json", "bad edit");

        assertTrue(registry.poll().isEmpty());
        assertEquals("one", registry.get("alpha").value());
    }

    @Test
    void ownWritesAreSuppressed() throws IOException {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = DocumentRegistry.folder("test", folder, PARSER, value -> 1L,
            file -> file.getName().equals("alpha.json"));
        registry.reload();

        write("alpha.json", "one rewritten");

        assertTrue(registry.poll().isEmpty());
        assertEquals("one", registry.get("alpha").value());
    }

    @Test
    void singleFileModeLoadsChangesAndRemoves() throws IOException {
        File file = new File(folder, "solo.json");
        Files.writeString(file.toPath(), "first");
        DocumentRegistry<String> registry = DocumentRegistry.singleFile("test", file, PARSER, value -> 1L, clock::get);

        registry.reload();
        assertEquals("first", registry.get("solo").value());

        Files.writeString(file.toPath(), "second value");
        assertTrue(file.setLastModified(file.lastModified() + 5000L));
        DocumentDelta changed = registry.poll();
        assertEquals(Set.of("solo"), Set.copyOf(changed.loaded()));
        assertEquals("first", registry.get("solo").value());
        assertEquals("second value", registry.get(changed, "solo").value());
        assertTrue(registry.acknowledge(changed));
        assertEquals("second value", registry.get("solo").value());

        assertTrue(file.delete());
        assertTrue(registry.poll().isEmpty());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3L));
        DocumentDelta removed = registry.poll();
        assertEquals(Set.of("solo"), Set.copyOf(removed.removed()));
        assertTrue(registry.acknowledge(removed));
        assertNull(registry.get("solo"));
    }

    @Test
    void pollReconcilesSameMetadataContentChanges() throws IOException {
        File file = new File(folder, "alpha.json");
        Files.writeString(file.toPath(), "one", StandardCharsets.UTF_8);
        DocumentRegistry<String> registry = registry();
        registry.reload();
        long timestamp = file.lastModified();

        Files.writeString(file.toPath(), "two", StandardCharsets.UTF_8);
        assertTrue(file.setLastModified(timestamp));

        DocumentDelta delta = registry.poll();
        assertEquals(Set.of("alpha"), Set.copyOf(delta.loaded()));
        assertEquals("one", registry.get("alpha").value());
        assertEquals("two", registry.get(delta, "alpha").value());
        assertTrue(registry.acknowledge(delta));
        assertEquals("two", registry.get("alpha").value());
    }

    @Test
    void briefDeleteAndRecreateDoesNotUnloadDocument() throws IOException {
        File file = new File(folder, "alpha.json");
        Files.writeString(file.toPath(), "one", StandardCharsets.UTF_8);
        DocumentRegistry<String> registry = registry();
        registry.reload();

        assertTrue(file.delete());
        assertTrue(registry.poll().isEmpty());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(2L));
        Files.writeString(file.toPath(), "two", StandardCharsets.UTF_8);

        DocumentDelta recreated = registry.poll();
        assertEquals(Set.of("alpha"), Set.copyOf(recreated.loaded()));
        assertEquals(Set.of(), Set.copyOf(recreated.removed()));
        assertEquals("two", registry.get(recreated, "alpha").value());
        assertTrue(registry.acknowledge(recreated));
        assertEquals("two", registry.get("alpha").value());
    }

    @Test
    void refusedDispatchKeepsCommittedStateAndRetriesOnce() throws IOException {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();
        write("alpha.json", "two");
        DocumentDelta first = registry.poll();
        AtomicInteger applications = new AtomicInteger();

        assertFalse(registry.dispatch(first, ignored -> false, applications::incrementAndGet));
        assertEquals("one", registry.get("alpha").value());
        assertEquals(0, applications.get());

        DocumentDelta retry = registry.poll();
        assertEquals(Set.of("alpha"), Set.copyOf(retry.loaded()));
        assertEquals("two", registry.get(retry, "alpha").value());
        assertTrue(registry.dispatch(retry, task -> {
            task.run();
            return true;
        }, applications::incrementAndGet));

        assertEquals(1, applications.get());
        assertEquals("two", registry.get("alpha").value());
        assertTrue(registry.poll().isEmpty());
    }

    @Test
    void failedApplyRetriesTheExactLatestState() throws IOException {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();
        write("alpha.json", "two");
        DocumentDelta first = registry.poll();
        AtomicInteger applications = new AtomicInteger();

        assertFalse(registry.apply(first, () -> {
            applications.incrementAndGet();
            throw new IllegalStateException("intentional apply failure");
        }));
        assertEquals("one", registry.get("alpha").value());

        write("alpha.json", "three");
        DocumentDelta retry = registry.poll();
        assertEquals(Set.of("alpha"), Set.copyOf(retry.loaded()));
        assertEquals("three", registry.get(retry, "alpha").value());
        assertTrue(registry.apply(retry, applications::incrementAndGet));

        assertEquals(2, applications.get());
        assertEquals("three", registry.get("alpha").value());
        assertTrue(registry.poll().isEmpty());
    }

    @Test
    void failedApplyDoesNotPublishAnOlderCandidateOverInvalidLatestBytes() throws IOException {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();
        write("alpha.json", "two");
        DocumentDelta first = registry.poll();

        assertFalse(registry.apply(first, () -> {
            throw new IllegalStateException("intentional apply failure");
        }));
        write("alpha.json", "bad latest");

        assertTrue(registry.poll().isEmpty());
        assertEquals("one", registry.get("alpha").value());

        write("alpha.json", "three");
        DocumentDelta retry = registry.poll();
        assertEquals("three", registry.get(retry, "alpha").value());
        assertTrue(registry.acknowledge(retry));
        assertEquals("three", registry.get("alpha").value());
    }

    @Test
    void acceptedCallbackCannotApplyTheSameDeltaTwice() throws IOException {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();
        write("alpha.json", "two");
        DocumentDelta delta = registry.poll();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicInteger applications = new AtomicInteger();

        assertTrue(registry.dispatch(delta, task -> {
            queued.set(task);
            return true;
        }, applications::incrementAndGet));
        assertEquals("one", registry.get("alpha").value());
        assertTrue(registry.poll().isEmpty());

        queued.get().run();
        queued.get().run();

        assertEquals(1, applications.get());
        assertEquals("two", registry.get("alpha").value());
        assertTrue(registry.poll().isEmpty());
    }
}
