package art.arcane.gloss.doc;

import art.arcane.volmlib.util.io.FileWatcher;
import art.arcane.volmlib.util.io.FolderWatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
    void pollReportsChangedCreatedAndDeletedDocuments() throws Exception {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();

        TrackingFolderWatcher changedWatcher = new TrackingFolderWatcher(folder);
        registry.replaceFolderWatcher(changedWatcher);
        write("alpha.json", "one edited");
        DocumentDelta first = awaitDelta(registry);
        assertTrue(changedWatcher.eventSeen());
        assertEquals(Set.of("alpha"), Set.copyOf(first.loaded()));
        assertEquals("one", registry.get("alpha").value());
        assertEquals("one edited", registry.get(first, "alpha").value());
        assertTrue(registry.acknowledge(first));

        TrackingFolderWatcher createdWatcher = new TrackingFolderWatcher(folder);
        registry.replaceFolderWatcher(createdWatcher);
        write("beta.json", "two");
        DocumentDelta created = awaitDelta(registry);
        assertTrue(createdWatcher.eventSeen());
        assertEquals(Set.of("beta"), Set.copyOf(created.loaded()));
        assertNull(registry.get("beta"));
        assertEquals("two", registry.get(created, "beta").value());
        assertTrue(registry.acknowledge(created));
        assertEquals("one edited", registry.get("alpha").value());
        assertEquals("two", registry.get("beta").value());

        TrackingFolderWatcher watcher = new TrackingFolderWatcher(folder);
        registry.replaceFolderWatcher(watcher);
        assertTrue(new File(folder, "beta.json").delete());
        awaitWatcherEvent(registry, watcher::eventSeen);
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
    void pollParseFailureKeepsLastGoodAndReportsNothing() throws Exception {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();
        TrackingFolderWatcher watcher = new TrackingFolderWatcher(folder);
        registry.replaceFolderWatcher(watcher);

        write("alpha.json", "bad edit");

        awaitWatcherEvent(registry, watcher::eventSeen);
        assertEquals("one", registry.get("alpha").value());
    }

    @Test
    void transientEmptySaveIsReplacedWithoutAWarning() throws Exception {
        AtomicInteger parses = new AtomicInteger();
        DocumentParser<String> parser = (fileName, raw) -> {
            parses.incrementAndGet();
            if (raw.isBlank()) {
                throw new IllegalArgumentException(fileName + " document is empty");
            }
            return raw.trim();
        };
        write("alpha.json", "one");
        DocumentRegistry<String> registry = DocumentRegistry.folder("test", folder, parser, value -> 1L,
            file -> false, clock::get);
        registry.reload();
        TrackingFolderWatcher watcher = new TrackingFolderWatcher(folder);
        registry.replaceFolderWatcher(watcher);
        List<LogRecord> warnings = new ArrayList<>();
        Handler handler = collectingHandler(warnings);
        Logger logger = Logger.getLogger("Gloss");
        logger.addHandler(handler);
        try {
            write("alpha.json", "");
            awaitWatcherEvent(registry, watcher::eventSeen);

            assertEquals(2, parses.get());
            assertTrue(warnings.isEmpty());
            assertEquals("one", registry.get("alpha").value());

            write("alpha.json", "two");
            DocumentDelta delta = awaitDelta(registry);

            assertEquals("two", registry.get(delta, "alpha").value());
            assertTrue(registry.acknowledge(delta));
            assertTrue(warnings.isEmpty());
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void unchangedInvalidSaveWarnsOnceAfterTheSecondObservation() throws Exception {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();
        TrackingFolderWatcher watcher = new TrackingFolderWatcher(folder);
        registry.replaceFolderWatcher(watcher);
        List<LogRecord> warnings = new ArrayList<>();
        Handler handler = collectingHandler(warnings);
        Logger logger = Logger.getLogger("Gloss");
        logger.addHandler(handler);
        try {
            write("alpha.json", "bad edit");
            awaitWatcherEvent(registry, watcher::eventSeen);

            assertTrue(warnings.isEmpty());
            assertTrue(registry.poll().isEmpty());
            assertEquals(1, warnings.size());
            assertTrue(registry.poll().isEmpty());
            assertEquals(1, warnings.size());
            assertEquals("one", registry.get("alpha").value());
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void changingInvalidBytesRestartsTheStabilityCheck() throws Exception {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();
        TrackingFolderWatcher firstWatcher = new TrackingFolderWatcher(folder);
        registry.replaceFolderWatcher(firstWatcher);
        List<LogRecord> warnings = new ArrayList<>();
        Handler handler = collectingHandler(warnings);
        Logger logger = Logger.getLogger("Gloss");
        logger.addHandler(handler);
        try {
            write("alpha.json", "bad first");
            awaitWatcherEvent(registry, firstWatcher::eventSeen);
            TrackingFolderWatcher secondWatcher = new TrackingFolderWatcher(folder);
            registry.replaceFolderWatcher(secondWatcher);
            write("alpha.json", "bad second");
            awaitWatcherEvent(registry, secondWatcher::eventSeen);

            assertTrue(warnings.isEmpty());
            assertTrue(registry.poll().isEmpty());
            assertEquals(1, warnings.size());
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void ownWritesAreSuppressed() throws Exception {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = DocumentRegistry.folder("test", folder, PARSER, value -> 1L,
            file -> file.getName().equals("alpha.json"));
        registry.reload();
        TrackingFolderWatcher watcher = new TrackingFolderWatcher(folder);
        registry.replaceFolderWatcher(watcher);

        write("alpha.json", "one rewritten");

        awaitWatcherEvent(registry, watcher::eventSeen);
        assertEquals("one", registry.get("alpha").value());
    }

    @Test
    void singleFileModeLoadsChangesAndRemoves() throws Exception {
        File file = new File(folder, "solo.json");
        Files.writeString(file.toPath(), "first");
        DocumentRegistry<String> registry = DocumentRegistry.singleFile("test", file, PARSER, value -> 1L, clock::get);

        registry.reload();
        assertEquals("first", registry.get("solo").value());

        TrackingFileWatcher changedWatcher = new TrackingFileWatcher(file);
        registry.replaceFileWatcher(changedWatcher);
        Files.writeString(file.toPath(), "second value");
        assertTrue(file.setLastModified(file.lastModified() + 5000L));
        DocumentDelta changed = awaitDelta(registry);
        assertTrue(changedWatcher.eventSeen());
        assertEquals(Set.of("solo"), Set.copyOf(changed.loaded()));
        assertEquals("first", registry.get("solo").value());
        assertEquals("second value", registry.get(changed, "solo").value());
        assertTrue(registry.acknowledge(changed));
        assertEquals("second value", registry.get("solo").value());

        TrackingFileWatcher watcher = new TrackingFileWatcher(file);
        registry.replaceFileWatcher(watcher);
        assertTrue(file.delete());
        awaitWatcherEvent(registry, watcher::eventSeen);
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
        registry.replaceFolderWatcher(new EventSilentFolderWatcher(folder));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(12L));

        DocumentDelta delta = registry.poll();
        assertEquals(Set.of("alpha"), Set.copyOf(delta.loaded()));
        assertEquals("one", registry.get("alpha").value());
        assertEquals("two", registry.get(delta, "alpha").value());
        assertTrue(registry.acknowledge(delta));
        assertEquals("two", registry.get("alpha").value());
    }

    @Test
    void exactContentReconciliationYieldsBetweenLargeFolderSlices() throws IOException {
        for (int index = 0; index < 96; index++) {
            Files.writeString(new File(folder, "entry-%03d.json".formatted(index)).toPath(), "one",
                StandardCharsets.UTF_8);
        }
        File last = new File(folder, "entry-095.json");
        long timestamp = last.lastModified();
        DocumentRegistry<String> registry = registry();
        registry.reload();
        registry.replaceFolderWatcher(new EventSilentFolderWatcher(folder));

        Files.writeString(last.toPath(), "two", StandardCharsets.UTF_8);
        assertTrue(last.setLastModified(timestamp));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(12L));

        assertTrue(registry.poll().isEmpty());
        DocumentDelta delta = DocumentDelta.EMPTY;
        for (int poll = 0; poll < 8 && delta.isEmpty(); poll++) {
            delta = registry.poll();
        }

        assertEquals(Set.of("entry-095"), Set.copyOf(delta.loaded()));
        assertEquals("two", registry.get(delta, "entry-095").value());
        assertTrue(registry.acknowledge(delta));
    }

    @Test
    void exactContentReconciliationPublishesOneCompleteSeparatedBatch() throws IOException {
        for (int index = 0; index < 96; index++) {
            Files.writeString(new File(folder, "entry-%03d.json".formatted(index)).toPath(), "one",
                StandardCharsets.UTF_8);
        }
        File first = new File(folder, "entry-000.json");
        File last = new File(folder, "entry-095.json");
        long firstTimestamp = first.lastModified();
        long lastTimestamp = last.lastModified();
        DocumentRegistry<String> registry = registry();
        registry.reload();
        registry.replaceFolderWatcher(new EventSilentFolderWatcher(folder));

        Files.writeString(first.toPath(), "two", StandardCharsets.UTF_8);
        Files.writeString(last.toPath(), "two", StandardCharsets.UTF_8);
        assertTrue(first.setLastModified(firstTimestamp));
        assertTrue(last.setLastModified(lastTimestamp));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(12L));

        assertTrue(registry.poll().isEmpty());
        assertTrue(registry.poll().isEmpty());
        DocumentDelta delta = DocumentDelta.EMPTY;
        for (int poll = 0; poll < 16 && delta.isEmpty(); poll++) {
            delta = registry.poll();
        }

        assertEquals(Set.of("entry-000", "entry-095"), Set.copyOf(delta.loaded()));
        assertEquals("two", registry.get(delta, "entry-000").value());
        assertEquals("two", registry.get(delta, "entry-095").value());
        assertTrue(registry.acknowledge(delta));
    }

    @Test
    void authoritativePublishDuringReconciliationIsNotReplayedAtCompletion() throws IOException {
        for (int index = 0; index < 96; index++) {
            Files.writeString(new File(folder, "entry-%03d.json".formatted(index)).toPath(), "one",
                StandardCharsets.UTF_8);
        }
        File first = new File(folder, "entry-000.json");
        File last = new File(folder, "entry-095.json");
        long firstTimestamp = first.lastModified();
        long lastTimestamp = last.lastModified();
        DocumentRegistry<String> registry = registry();
        registry.reload();
        registry.replaceFolderWatcher(new EventSilentFolderWatcher(folder));
        Files.writeString(first.toPath(), "two", StandardCharsets.UTF_8);
        Files.writeString(last.toPath(), "two", StandardCharsets.UTF_8);
        assertTrue(first.setLastModified(firstTimestamp));
        assertTrue(last.setLastModified(lastTimestamp));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(6L));

        assertTrue(registry.poll().isEmpty());
        Files.writeString(first.toPath(), "three", StandardCharsets.UTF_8);
        registry.publish("entry-000", "three", "three");
        DocumentDelta delta = DocumentDelta.EMPTY;
        for (int poll = 0; poll < 16 && delta.isEmpty(); poll++) {
            delta = registry.poll();
        }

        assertEquals(Set.of("entry-095"), Set.copyOf(delta.loaded()));
        assertEquals("three", registry.get("entry-000").value());
    }

    @Test
    void authoritativeRemoveDuringReconciliationIsNotReplayedAtCompletion() throws IOException {
        for (int index = 0; index < 96; index++) {
            Files.writeString(new File(folder, "entry-%03d.json".formatted(index)).toPath(), "one",
                StandardCharsets.UTF_8);
        }
        File first = new File(folder, "entry-000.json");
        File last = new File(folder, "entry-095.json");
        long firstTimestamp = first.lastModified();
        long lastTimestamp = last.lastModified();
        DocumentRegistry<String> registry = registry();
        registry.reload();
        registry.replaceFolderWatcher(new EventSilentFolderWatcher(folder));
        Files.writeString(first.toPath(), "two", StandardCharsets.UTF_8);
        Files.writeString(last.toPath(), "two", StandardCharsets.UTF_8);
        assertTrue(first.setLastModified(firstTimestamp));
        assertTrue(last.setLastModified(lastTimestamp));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(6L));

        assertTrue(registry.poll().isEmpty());
        assertTrue(registry.remove("entry-000"));
        DocumentDelta delta = DocumentDelta.EMPTY;
        for (int poll = 0; poll < 16 && delta.isEmpty(); poll++) {
            delta = registry.poll();
        }

        assertEquals(Set.of("entry-095"), Set.copyOf(delta.loaded()));
        assertNull(registry.get("entry-000"));
    }

    @Test
    void fullScanDeadlineBeginsWhenTheScanCompletes() {
        DocumentRegistry<String> registry = DocumentRegistry.folder("even", folder, PARSER, value -> 1L,
            file -> false, clock::get);
        registry.reload();
        ClockAdvancingFolderWatcher watcher = new ClockAdvancingFolderWatcher(folder, clock);
        registry.replaceFolderWatcher(watcher);

        clock.set(TimeUnit.SECONDS.toNanos(18L));
        registry.poll();
        assertEquals(1, watcher.fullScans());
        registry.poll();
        assertEquals(1, watcher.fullScans());
        clock.set(TimeUnit.SECONDS.toNanos(55L));
        registry.poll();
        assertEquals(1, watcher.fullScans());
        clock.set(TimeUnit.SECONDS.toNanos(56L));
        registry.poll();
        assertEquals(2, watcher.fullScans());
    }

    @Test
    void singleFileDeadlineBeginsWhenContentProcessingCompletes() throws IOException {
        File file = new File(folder, "solo.json");
        Files.writeString(file.toPath(), "one", StandardCharsets.UTF_8);
        AtomicInteger parses = new AtomicInteger();
        DocumentParser<String> advancingParser = (fileName, raw) -> {
            parses.incrementAndGet();
            if (!raw.equals("one")) {
                clock.addAndGet(TimeUnit.SECONDS.toNanos(7L));
            }
            return raw;
        };
        DocumentRegistry<String> registry = DocumentRegistry.singleFile("even", file, advancingParser,
            value -> 1L, clock::get);
        registry.reload();
        registry.replaceFileWatcher(new EventSilentFileWatcher(file));
        Files.writeString(file.toPath(), "two", StandardCharsets.UTF_8);
        clock.set(TimeUnit.SECONDS.toNanos(6L));

        DocumentDelta first = registry.poll();
        assertEquals(Set.of("solo"), Set.copyOf(first.loaded()));
        assertTrue(registry.acknowledge(first));
        assertEquals(2, parses.get());
        Files.writeString(file.toPath(), "three", StandardCharsets.UTF_8);
        assertTrue(registry.poll().isEmpty());
        clock.set(TimeUnit.SECONDS.toNanos(18L));
        assertTrue(registry.poll().isEmpty());
        clock.set(TimeUnit.SECONDS.toNanos(19L));
        assertEquals(Set.of("solo"), Set.copyOf(registry.poll().loaded()));
    }

    @Test
    void knownRegistryKindsAreBalancedAcrossThreeSecondSlots() {
        List<String> kinds = List.of("animations", "boards", "bubbles", "menus", "real-drops",
            "emoji", "holograms", "motd", "tablist", "previews");
        long firstSlot = 0L;
        long secondSlot = TimeUnit.SECONDS.toNanos(3L);

        assertEquals(5L, kinds.stream()
            .filter(kind -> DocumentRegistry.reconciliationInitialOffsetNanos(kind) == firstSlot).count());
        assertEquals(5L, kinds.stream()
            .filter(kind -> DocumentRegistry.reconciliationInitialOffsetNanos(kind) == secondSlot).count());
    }

    @Test
    void oneWatchdogPassSharesOneAggregateReconciliationBudget() throws IOException {
        File firstFolder = new File(folder, "first");
        File secondFolder = new File(folder, "second");
        assertTrue(firstFolder.mkdirs());
        assertTrue(secondFolder.mkdirs());
        for (int index = 0; index < HotloadReconciliationBudget.FILE_LIMIT; index++) {
            Files.writeString(new File(firstFolder, "entry-%03d.json".formatted(index)).toPath(), "one");
            Files.writeString(new File(secondFolder, "entry-%03d.json".formatted(index)).toPath(), "one");
        }
        DocumentRegistry<String> first = DocumentRegistry.folder("even", firstFolder, PARSER, value -> 1L,
            file -> false, clock::get);
        DocumentRegistry<String> second = DocumentRegistry.folder("also", secondFolder, PARSER, value -> 1L,
            file -> false, clock::get);
        first.reload();
        second.reload();
        first.replaceFolderWatcher(new EventSilentFolderWatcher(firstFolder));
        second.replaceFolderWatcher(new EventSilentFolderWatcher(secondFolder));
        for (int index = 0; index < HotloadReconciliationBudget.FILE_LIMIT; index++) {
            Files.writeString(new File(firstFolder, "entry-%03d.json".formatted(index)).toPath(), "two");
            Files.writeString(new File(secondFolder, "entry-%03d.json".formatted(index)).toPath(), "two");
        }
        clock.set(TimeUnit.SECONDS.toNanos(6L));

        DocumentDelta firstDelta;
        DocumentDelta secondDelta;
        try (HotloadReconciliationBudget budget = HotloadReconciliationBudget.open(clock::get)) {
            firstDelta = first.poll();
            secondDelta = second.poll();
            assertEquals(HotloadReconciliationBudget.FILE_LIMIT, budget.files());
        }

        assertEquals(HotloadReconciliationBudget.FILE_LIMIT, firstDelta.loaded().size());
        assertTrue(secondDelta.isEmpty());
        assertTrue(first.acknowledge(firstDelta));
        try (HotloadReconciliationBudget budget = HotloadReconciliationBudget.open(clock::get)) {
            secondDelta = second.poll();
        }
        assertEquals(HotloadReconciliationBudget.FILE_LIMIT, secondDelta.loaded().size());
    }

    @Test
    void singleFileIdlePollDefersSilentContentReadUntilReconciliationWindow() throws IOException {
        File file = new File(folder, "solo.json");
        Files.writeString(file.toPath(), "one", StandardCharsets.UTF_8);
        long timestamp = file.lastModified();
        DocumentRegistry<String> registry = DocumentRegistry.singleFile("test", file, PARSER, value -> 1L, clock::get);
        registry.reload();
        registry.replaceFileWatcher(new EventSilentFileWatcher(file));

        Files.writeString(file.toPath(), "two", StandardCharsets.UTF_8);
        assertTrue(file.setLastModified(timestamp));

        assertTrue(registry.poll().isEmpty());
        assertEquals("one", registry.get("solo").value());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(12L));

        DocumentDelta delta = registry.poll();
        assertEquals(Set.of("solo"), Set.copyOf(delta.loaded()));
        assertEquals("two", registry.get(delta, "solo").value());
    }

    @Test
    void briefDeleteAndRecreateDoesNotUnloadDocument() throws Exception {
        File file = new File(folder, "alpha.json");
        Files.writeString(file.toPath(), "one", StandardCharsets.UTF_8);
        DocumentRegistry<String> registry = registry();
        registry.reload();

        TrackingFolderWatcher watcher = new TrackingFolderWatcher(folder);
        registry.replaceFolderWatcher(watcher);
        assertTrue(file.delete());
        awaitWatcherEvent(registry, watcher::eventSeen);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(2L));
        Files.writeString(file.toPath(), "two", StandardCharsets.UTF_8);

        DocumentDelta recreated = awaitDelta(registry);
        assertEquals(Set.of("alpha"), Set.copyOf(recreated.loaded()));
        assertEquals(Set.of(), Set.copyOf(recreated.removed()));
        assertEquals("two", registry.get(recreated, "alpha").value());
        assertTrue(registry.acknowledge(recreated));
        assertEquals("two", registry.get("alpha").value());
    }

    @Test
    void refusedDispatchKeepsCommittedStateAndRetriesOnce() throws Exception {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();
        write("alpha.json", "two");
        clock.addAndGet(TimeUnit.SECONDS.toNanos(12L));
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
    void failedApplyRetriesTheExactLatestState() throws Exception {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();
        write("alpha.json", "two");
        clock.addAndGet(TimeUnit.SECONDS.toNanos(12L));
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
    void failedApplyDoesNotPublishAnOlderCandidateOverInvalidLatestBytes() throws Exception {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();
        write("alpha.json", "two");
        clock.addAndGet(TimeUnit.SECONDS.toNanos(12L));
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
    void acceptedCallbackCannotApplyTheSameDeltaTwice() throws Exception {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();
        write("alpha.json", "two");
        clock.addAndGet(TimeUnit.SECONDS.toNanos(12L));
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

    private static DocumentDelta awaitDelta(DocumentRegistry<String> registry) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        DocumentDelta delta = DocumentDelta.EMPTY;
        while (delta.isEmpty() && System.nanoTime() < deadline) {
            delta = registry.poll();
            if (delta.isEmpty()) {
                Thread.sleep(25L);
            }
        }
        assertFalse(delta.isEmpty());
        return delta;
    }

    private static void awaitWatcherEvent(DocumentRegistry<String> registry, BooleanSupplier eventSeen)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (!eventSeen.getAsBoolean() && System.nanoTime() < deadline) {
            registry.poll();
            if (!eventSeen.getAsBoolean()) {
                Thread.sleep(25L);
            }
        }
        assertTrue(eventSeen.getAsBoolean());
    }

    private static Handler collectingHandler(List<LogRecord> records) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage() != null
                    && record.getMessage().startsWith("[Gloss] test/alpha.json:")) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    private static final class EventSilentFolderWatcher extends FolderWatcher {
        private EventSilentFolderWatcher(File folder) {
            super(folder);
        }

        @Override
        public boolean checkModified() {
            return false;
        }

        @Override
        public boolean checkModifiedEvents() {
            return false;
        }
    }

    private static final class ClockAdvancingFolderWatcher extends FolderWatcher {
        private final AtomicLong clock;
        private int fullScans;

        private ClockAdvancingFolderWatcher(File folder, AtomicLong clock) {
            super(folder);
            this.clock = clock;
        }

        @Override
        public boolean checkModified() {
            fullScans++;
            clock.addAndGet(TimeUnit.SECONDS.toNanos(20L));
            return false;
        }

        @Override
        public boolean checkModifiedEvents() {
            return false;
        }

        private int fullScans() {
            return fullScans;
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

    private static final class TrackingFolderWatcher extends FolderWatcher {
        private boolean eventSeen;

        private TrackingFolderWatcher(File folder) {
            super(folder);
        }

        @Override
        public boolean checkModifiedEvents() {
            boolean modified = super.checkModifiedEvents();
            if (!modified) {
                modified = super.checkModified();
            }
            eventSeen |= modified;
            return modified;
        }

        private boolean eventSeen() {
            return eventSeen;
        }
    }

    private static final class TrackingFileWatcher extends FileWatcher {
        private boolean eventSeen;

        private TrackingFileWatcher(File file) {
            super(file);
        }

        @Override
        public boolean checkModifiedEvents() {
            boolean modified = super.checkModifiedEvents();
            if (!modified) {
                modified = super.checkModified();
            }
            eventSeen |= modified;
            return modified;
        }

        private boolean eventSeen() {
            return eventSeen;
        }
    }
}
