package art.arcane.gloss.doc;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.io.FileWatcher;
import art.arcane.volmlib.util.io.FolderWatcher;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

public final class DocumentRegistry<T> implements AutoCloseable {
    private static final String EXTENSION = ".json";
    static final long MAX_DOCUMENT_BYTES = 2L * 1024L * 1024L;
    private static final long RECONCILIATION_BYTE_BUDGET = 8L * 1024L * 1024L;
    private static final long RECONCILIATION_TIME_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(10L);
    private static final long FULL_WATCH_SCAN_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(18L);
    static final long CONTENT_RECONCILIATION_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(6L);
    private static final long RECONCILIATION_SLOT_NANOS = TimeUnit.SECONDS.toNanos(3L);
    private static final int RECONCILIATION_SLOT_COUNT = 2;
    static final int RECONCILIATION_FILE_BUDGET = 32;
    private static final long DELETION_GRACE_NANOS = TimeUnit.SECONDS.toNanos(3L);

    /**
     * The revision of a kind that carries no v2 envelope. Its identity is the content hash on
     * {@link GlossDocument}, which is what a caller compares to detect a real change.
     */
    public static final long UNVERSIONED = 0L;

    private enum Layout {
        /** One named file. */
        FILE,
        /** Direct {@code .json} children of one folder. */
        FOLDER,
        /** Every {@code .json} file below one folder, ids carrying their subdirectory path. */
        TREE
    }

    private final String kind;
    private final File target;
    private final Layout layout;
    private final DocumentParser<T> parser;
    private final ToLongFunction<T> revisionOf;
    private final Predicate<File> ownWrite;
    private final LongSupplier clock;
    private final Map<String, GlossDocument<T>> documents;
    private final Map<String, Long> pendingDeletions;
    private final Map<String, String> pendingFailureFingerprints;
    private final Map<String, String> reportedFailureFingerprints;
    private final Map<String, String> ignoredSchemaFingerprints;
    private final Set<String> failureRetryIds;
    private final Set<String> retryIds;
    private final Set<String> failedIds;
    private final Set<String> reconciliationLoaded;
    private final Set<String> ownerWrites;
    private final long reconciliationInitialOffsetNanos;
    private volatile Map<String, GlossDocument<T>> snapshot;
    private volatile FolderWatcher folderWatcher;
    private volatile FileWatcher fileWatcher;
    private List<File> reconciliationFiles;
    private int reconciliationIndex;
    private long nextFullWatchScanNanos;
    private long nextContentReconciliationNanos;
    private boolean reconciliationInProgress;
    private boolean polling;
    private boolean pollInvalidated;
    private DocumentDelta pendingDelta;
    private Map<String, GlossDocument<T>> pendingSnapshot;
    private PendingState pendingState;

    private enum PendingState {
        READY,
        QUEUED,
        APPLYING
    }

    private DocumentRegistry(String kind, File target, Layout layout, DocumentParser<T> parser,
                             ToLongFunction<T> revisionOf, Predicate<File> ownWrite, LongSupplier clock) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.target = Objects.requireNonNull(target, "target");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.revisionOf = Objects.requireNonNull(revisionOf, "revisionOf");
        this.ownWrite = Objects.requireNonNull(ownWrite, "ownWrite");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.documents = new ConcurrentHashMap<>();
        this.pendingDeletions = new HashMap<>();
        this.pendingFailureFingerprints = new HashMap<>();
        this.reportedFailureFingerprints = new HashMap<>();
        this.ignoredSchemaFingerprints = new ConcurrentHashMap<>();
        this.failureRetryIds = new HashSet<>();
        this.retryIds = new HashSet<>();
        this.failedIds = new HashSet<>();
        this.reconciliationLoaded = new HashSet<>();
        this.ownerWrites = new HashSet<>();
        this.reconciliationInitialOffsetNanos = reconciliationInitialOffsetNanos(kind);
        this.snapshot = Map.of();
        this.reconciliationFiles = List.of();
        this.pendingSnapshot = Map.of();
        scheduleReconciliationWindows();
    }

    public static <T> DocumentRegistry<T> folder(String kind, File folder, DocumentParser<T> parser,
                                                 ToLongFunction<T> revisionOf) {
        return folder(kind, folder, parser, revisionOf, file -> false);
    }

    public static <T> DocumentRegistry<T> folder(String kind, File folder, DocumentParser<T> parser,
                                                 ToLongFunction<T> revisionOf, Predicate<File> ownWrite) {
        return folder(kind, folder, parser, revisionOf, ownWrite, System::nanoTime);
    }

    static <T> DocumentRegistry<T> folder(String kind, File folder, DocumentParser<T> parser,
                                          ToLongFunction<T> revisionOf, Predicate<File> ownWrite,
                                          LongSupplier clock) {
        return new DocumentRegistry<>(kind, folder, Layout.FOLDER, parser, revisionOf, ownWrite, clock);
    }

    /**
     * A folder whose subdirectories are part of the id: {@code menus/archive/old.json} is the
     * document {@code archive/old}. {@link DocumentTree} owns the path rules.
     */
    public static <T> DocumentRegistry<T> folderTree(String kind, File folder, DocumentParser<T> parser,
                                                     ToLongFunction<T> revisionOf) {
        return folderTree(kind, folder, parser, revisionOf, System::nanoTime);
    }

    static <T> DocumentRegistry<T> folderTree(String kind, File folder, DocumentParser<T> parser,
                                              ToLongFunction<T> revisionOf, LongSupplier clock) {
        return new DocumentRegistry<>(kind, folder, Layout.TREE, parser, revisionOf, file -> false, clock);
    }

    public static <T> DocumentRegistry<T> singleFile(String kind, File file, DocumentParser<T> parser,
                                                     ToLongFunction<T> revisionOf) {
        return singleFile(kind, file, parser, revisionOf, System::nanoTime);
    }

    static <T> DocumentRegistry<T> singleFile(String kind, File file, DocumentParser<T> parser,
                                              ToLongFunction<T> revisionOf, LongSupplier clock) {
        return new DocumentRegistry<>(kind, file, Layout.FILE, parser, revisionOf, target -> false, clock);
    }

    public String kind() {
        return kind;
    }

    public Map<String, GlossDocument<T>> snapshot() {
        return snapshot;
    }

    public synchronized Map<String, GlossDocument<T>> snapshot(DocumentDelta delta) {
        return pendingDelta == delta ? pendingSnapshot : snapshot;
    }

    public GlossDocument<T> get(String id) {
        return id == null ? null : snapshot.get(id);
    }

    public synchronized GlossDocument<T> get(DocumentDelta delta, String id) {
        if (id == null) {
            return null;
        }
        return (pendingDelta == delta ? pendingSnapshot : snapshot).get(id);
    }

    public Set<String> ids() {
        return Set.copyOf(snapshot.keySet());
    }

    /**
     * Adopts a document the owner just wrote, without waiting for the watcher to read it back. The
     * next poll finds the same bytes on disk and reports nothing, so a write publishes exactly once.
     */
    public synchronized GlossDocument<T> publish(String id, String raw, T value) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(value, "value");
        invalidatePending(id);
        recordOwnerWrite(id);
        clearFailure(id);
        ignoredSchemaFingerprints.remove(id);
        reconciliationLoaded.remove(id);
        GlossDocument<T> document = GlossDocument.of(id, raw, value, revisionOf.applyAsLong(value));
        documents.put(id, document);
        pendingDeletions.remove(id);
        commit(id, document);
        return document;
    }

    /** Drops a document the owner just deleted or rolled back. */
    public synchronized boolean remove(String id) {
        if (id == null) {
            return false;
        }
        invalidatePending(id);
        recordOwnerWrite(id);
        clearFailure(id);
        ignoredSchemaFingerprints.remove(id);
        reconciliationLoaded.remove(id);
        pendingDeletions.remove(id);
        boolean workingRemoved = documents.remove(id) != null;
        if (!workingRemoved && !snapshot.containsKey(id)) {
            return false;
        }
        commit(id, null);
        return true;
    }

    /**
     * Loads every document currently on disk. A missing folder is an empty registry, never a folder
     * Gloss creates: the folder appears when something writes into it, and {@link FolderWatcher}
     * reports its contents as creations on the poll after that.
     */
    public synchronized void reload() {
        pollInvalidated = polling;
        clearPending();
        retryIds.clear();
        failedIds.clear();
        failureRetryIds.clear();
        pendingFailureFingerprints.clear();
        reportedFailureFingerprints.clear();
        ignoredSchemaFingerprints.clear();
        pendingDeletions.clear();
        resetReconciliation();
        if (layout == Layout.FILE) {
            reloadSingle();
            return;
        }
        Set<String> present = new HashSet<>();
        for (File file : currentFiles()) {
            String id = idOf(file);
            present.add(id);
            load(id, file, false);
        }
        documents.keySet().retainAll(present);
        replaceFolderWatcher(new FolderWatcher(target));
        publish();
    }

    /**
     * One hot-reload pass. The directory walk, the reads and the parses all run off the registry
     * monitor and land in locals; the monitor is taken only to apply what the pass already holds,
     * so a main-thread {@code get}, {@code snapshot} or apply never queues behind a stat walk of
     * the document folder. The pending-delta state machine is unchanged: a pass that finds nothing
     * stages nothing, and a pass that races an authoritative {@code publish}/{@code remove} drops
     * exactly the ids the owner just settled.
     */
    public DocumentDelta poll() {
        return layout == Layout.FILE ? pollSingle() : pollFolder();
    }

    private DocumentDelta pollFolder() {
        PollStart start = beginPoll();
        if (start == null) {
            return DocumentDelta.EMPTY;
        }
        try {
            List<String> loaded = new ArrayList<>();
            SweepPlan plan = applyWatched(start, readWatched(start), loaded);
            if (plan == null) {
                return DocumentDelta.EMPTY;
            }
            return applySweep(plan, readSweep(plan), loaded);
        } finally {
            endPoll();
        }
    }

    private synchronized PollStart beginPoll() {
        if (pendingDelta != null) {
            return null;
        }
        failedIds.clear();
        polling = true;
        pollInvalidated = false;
        ownerWrites.clear();
        long now = clock.getAsLong();
        String singleId = layout == Layout.FILE ? baseName(target) : null;
        return new PollStart(folderWatcher, fileWatcher, now >= nextFullWatchScanNanos,
            now >= nextContentReconciliationNanos,
            singleId != null && (retryIds.contains(singleId) || failureRetryIds.contains(singleId)), now);
    }

    private synchronized void endPoll() {
        polling = false;
        pollInvalidated = false;
        ownerWrites.clear();
    }

    private synchronized void recordOwnerWrite(String id) {
        if (polling) {
            ownerWrites.add(id);
        }
    }

    /** The full-scan window opens again when the scan finishes, not when it started. */
    private synchronized void recordFullWatchScan() {
        nextFullWatchScanNanos = clock.getAsLong() + FULL_WATCH_SCAN_WINDOW_NANOS;
    }

    private synchronized void recordContentReconciliation() {
        nextContentReconciliationNanos = clock.getAsLong() + CONTENT_RECONCILIATION_WINDOW_NANOS;
    }

    private WatchScan<T> readWatched(PollStart start) {
        FolderWatcher watcher = start.folderWatcher();
        boolean changed = false;
        try {
            changed = watcher != null
                && (start.fullWatchScan() ? watcher.checkModified() : watcher.checkModifiedEvents());
        } finally {
            if (start.fullWatchScan()) {
                recordFullWatchScan();
            }
        }
        if (!changed) {
            return new WatchScan<>(false, List.of(), List.of());
        }
        List<PreparedLoad<T>> touched = new ArrayList<>();
        for (File file : watcher.getChanged()) {
            readTouched(file, touched, false);
        }
        for (File file : watcher.getCreated()) {
            readTouched(file, touched, true);
        }
        List<DeletedCandidate> deleted = new ArrayList<>();
        for (File file : watcher.getDeleted()) {
            if (file != null) {
                deleted.add(readDeleted(file));
            }
        }
        return new WatchScan<>(true, List.copyOf(touched), List.copyOf(deleted));
    }

    /** Ids still worth reading this pass: one that already failed is not re-read until the next. */
    private List<String> pending(Set<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (!failedIds.contains(id)) {
                candidates.add(id);
            }
        }
        return List.copyOf(candidates);
    }

    private synchronized SweepPlan applyWatched(PollStart start, WatchScan<T> scan, List<String> loaded) {
        if (pollInvalidated || pendingDelta != null) {
            return null;
        }
        if (scan.changed()) {
            for (PreparedLoad<T> prepared : scan.touched()) {
                acceptPrepared(prepared, loaded);
            }
            for (DeletedCandidate candidate : scan.deleted()) {
                applyDeleted(candidate);
            }
            reconciliationLoaded.removeAll(loaded);
            reconciliationLoaded.removeAll(pendingDeletions.keySet());
        }
        startContentReconciliation(start.now());
        return new SweepPlan(reconciliationInProgress, reconciliationFiles, reconciliationIndex,
            Set.copyOf(failedIds), pending(retryIds), pending(failureRetryIds));
    }

    /**
     * Reads the content-reconciliation slice and every retry candidate. The slice honours the same
     * file, byte and time budgets as before; they now measure the read and the parse, which is the
     * work they were always meant to bound.
     */
    private Sweep<T> readSweep(SweepPlan plan) {
        List<PreparedLoad<T>> reconciled = new ArrayList<>();
        int index = plan.index();
        if (plan.active()) {
            long startedAt = HotloadReconciliationBudget.nanoTime();
            long bytes = 0L;
            int files = 0;
            while (index < plan.files().size() && files < RECONCILIATION_FILE_BUDGET) {
                if (files > 0
                    && HotloadReconciliationBudget.nanoTime() - startedAt >= RECONCILIATION_TIME_BUDGET_NANOS) {
                    break;
                }
                File file = plan.files().get(index);
                long size = file.isFile() ? Math.max(0L, file.length()) : 0L;
                if (files > 0 && bytes + Math.min(size, MAX_DOCUMENT_BYTES) > RECONCILIATION_BYTE_BUDGET) {
                    break;
                }
                if (!HotloadReconciliationBudget.tryAcquire(size)) {
                    break;
                }
                index++;
                files++;
                bytes += Math.min(size, MAX_DOCUMENT_BYTES);
                String id = idOf(file);
                if (file.isFile() && !plan.failed().contains(id)) {
                    reconciled.add(readCandidate(id, file));
                }
            }
        }
        return new Sweep<>(List.copyOf(reconciled), index,
            readRetries(plan.retryIds()), readRetries(plan.failureRetryIds()));
    }

    private List<PreparedLoad<T>> readRetries(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<PreparedLoad<T>> prepared = new ArrayList<>(ids.size());
        for (String id : ids) {
            File file = fileForId(id);
            prepared.add(file.isFile() ? readCandidate(id, file) : PreparedLoad.missing(id, file));
        }
        return List.copyOf(prepared);
    }

    private synchronized DocumentDelta applySweep(SweepPlan plan, Sweep<T> sweep, List<String> loaded) {
        if (pollInvalidated || pendingDelta != null) {
            return DocumentDelta.EMPTY;
        }
        if (plan.active() && reconciliationInProgress && reconciliationFiles == plan.files()) {
            applyReconciliation(sweep, loaded);
        }
        for (PreparedLoad<T> prepared : sweep.retries()) {
            if (failedIds.contains(prepared.id()) || prepared.missing()) {
                continue;
            }
            acceptPrepared(prepared, loaded);
        }
        for (PreparedLoad<T> prepared : sweep.failureRetries()) {
            if (failedIds.contains(prepared.id())) {
                continue;
            }
            if (prepared.missing()) {
                clearFailure(prepared.id());
                continue;
            }
            acceptPrepared(prepared, loaded);
        }
        List<String> removed = new ArrayList<>();
        applyMatureDeletions(removed);
        return stage(loaded, removed);
    }

    private void applyReconciliation(Sweep<T> sweep, List<String> loaded) {
        List<String> sliceLoaded = new ArrayList<>();
        for (PreparedLoad<T> prepared : sweep.reconciled()) {
            acceptPrepared(prepared, sliceLoaded);
        }
        reconciliationIndex = sweep.nextIndex();
        reconciliationLoaded.addAll(sliceLoaded);
        if (reconciliationIndex < reconciliationFiles.size()) {
            return;
        }
        reconciliationFiles = List.of();
        reconciliationIndex = 0;
        reconciliationInProgress = false;
        nextContentReconciliationNanos = clock.getAsLong() + CONTENT_RECONCILIATION_WINDOW_NANOS;
        for (String id : reconciliationLoaded) {
            if (!loaded.contains(id)) {
                loaded.add(id);
            }
        }
        reconciliationLoaded.clear();
    }

    public synchronized boolean acknowledge(DocumentDelta delta) {
        return apply(delta, () -> {
        });
    }

    public synchronized boolean apply(DocumentDelta delta, Runnable application) {
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(application, "application");
        if (!claim(delta, PendingState.READY)) {
            return false;
        }
        return runApplication(delta, application);
    }

    public synchronized boolean dispatch(DocumentDelta delta, Function<Runnable, Boolean> dispatcher,
                                         Runnable application) {
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(application, "application");
        if (!claim(delta, PendingState.READY)) {
            return false;
        }
        pendingState = PendingState.QUEUED;
        boolean scheduled;
        try {
            scheduled = Boolean.TRUE.equals(dispatcher.apply(() -> runDispatched(delta, application)));
        } catch (ThreadDeath fatal) {
            retry(delta);
            throw fatal;
        } catch (Throwable failure) {
            retry(delta);
            Gloss.logExceptionStack(false, failure, "%s: hot reload scheduling failed.", kind);
            return false;
        }
        if (!scheduled && pendingDelta == delta) {
            retry(delta);
        }
        return scheduled;
    }

    public synchronized boolean prepareDispatch(DocumentDelta delta, Function<Runnable, Boolean> dispatcher,
                                                Supplier<Runnable> preparation) {
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(preparation, "preparation");
        if (!claim(delta, PendingState.READY)) {
            return false;
        }
        pendingState = PendingState.APPLYING;
        Runnable application;
        try {
            application = Objects.requireNonNull(preparation.get(), "prepared application");
        } catch (ThreadDeath fatal) {
            retry(delta);
            throw fatal;
        } catch (Throwable failure) {
            retry(delta);
            Gloss.logExceptionStack(false, failure, "%s: hot reload preparation failed.", kind);
            return false;
        }
        pendingState = PendingState.READY;
        return dispatch(delta, dispatcher, application);
    }

    private List<File> currentFiles() {
        if (layout == Layout.TREE) {
            return DocumentTree.discover(target);
        }
        File[] files = target.listFiles();
        if (files == null) {
            return List.of();
        }
        List<File> present = new ArrayList<>(files.length);
        for (File file : files) {
            if (isFolderDocument(file)) {
                present.add(file);
            }
        }
        present.sort(Comparator.comparing(File::getAbsolutePath));
        return present;
    }

    /**
     * A watcher reports a new subdirectory as one creation, so a creation in a tree is walked to
     * reach the files it arrived with. A change is not: the watcher reports the directory whose
     * contents moved as well as the file that moved, and walking it would re-read the whole subtree
     * for every edit inside it. A flat folder only ever sees its own children.
     */
    private void readTouched(File file, List<PreparedLoad<T>> prepared, boolean walk) {
        if (layout == Layout.TREE) {
            if (walk) {
                for (File document : DocumentTree.discover(target, file)) {
                    prepared.add(readCandidate(idOf(document), document));
                }
            } else if (DocumentTree.isDocument(target, file)) {
                prepared.add(readCandidate(idOf(file), file));
            }
            return;
        }
        if (!isFolderDocument(file) || !isDirectChild(file)) {
            return;
        }
        prepared.add(readCandidate(idOf(file), file));
    }

    private void acceptPrepared(PreparedLoad<T> prepared, List<String> loaded) {
        String id = prepared.id();
        pendingDeletions.remove(id);
        if (failedIds.contains(id) || prepared.ownWrite() || ownerWrites.contains(id)) {
            return;
        }
        if (applyPrepared(prepared, true) && !loaded.contains(id)) {
            loaded.add(id);
        }
    }

    /** Everything a deletion decision needs from disk, resolved before the monitor is taken. */
    private DeletedCandidate readDeleted(File file) {
        if (file.exists()) {
            return new DeletedCandidate(file, true, false, null);
        }
        if (layout != Layout.TREE) {
            return new DeletedCandidate(file, false, false, null);
        }
        return new DeletedCandidate(file, false, DocumentTree.isDocument(target, file),
            DocumentTree.prefixOf(target, file));
    }

    private void applyDeleted(DeletedCandidate candidate) {
        File file = candidate.file();
        if (candidate.exists()) {
            if (isDocument(file)) {
                pendingDeletions.remove(idOf(file));
            }
            return;
        }
        if (layout == Layout.TREE) {
            if (candidate.treeDocument()) {
                markDeleted(DocumentTree.idOf(target, file));
            }
            String prefix = candidate.treePrefix();
            if (prefix == null) {
                return;
            }
            String nested = prefix + "/";
            for (String id : List.copyOf(documents.keySet())) {
                if (id.startsWith(nested)) {
                    markDeleted(id);
                }
            }
            return;
        }
        if (isDocument(file) && isDirectChild(file)) {
            markDeleted(baseName(file));
        }
    }

    private void removeLoaded(String id, List<String> removed) {
        if (documents.remove(id) != null) {
            clearFailure(id);
            ignoredSchemaFingerprints.remove(id);
            removed.add(id);
        }
    }

    private void reloadSingle() {
        if (target.isFile()) {
            load(baseName(target), target, false);
        } else {
            documents.remove(baseName(target));
        }
        replaceFileWatcher(new FileWatcher(target));
        publish();
    }

    @Override
    public synchronized void close() {
        pollInvalidated = polling;
        replaceFolderWatcher(null);
        replaceFileWatcher(null);
        clearPending();
        retryIds.clear();
        failedIds.clear();
        failureRetryIds.clear();
        pendingFailureFingerprints.clear();
        reportedFailureFingerprints.clear();
        ignoredSchemaFingerprints.clear();
        reconciliationLoaded.clear();
        pendingDeletions.clear();
        resetReconciliation();
    }

    void replaceFolderWatcher(FolderWatcher replacement) {
        FolderWatcher previous = folderWatcher;
        folderWatcher = replacement;
        if (previous != null) {
            previous.close();
        }
    }

    void replaceFileWatcher(FileWatcher replacement) {
        FileWatcher previous = fileWatcher;
        fileWatcher = replacement;
        if (previous != null) {
            previous.close();
        }
    }

    private DocumentDelta pollSingle() {
        PollStart start = beginPoll();
        if (start == null) {
            return DocumentDelta.EMPTY;
        }
        boolean reconcileContent = start.reconcileContent();
        try {
            return applySingle(readSingle(start, reconcileContent), reconcileContent);
        } finally {
            if (reconcileContent) {
                recordContentReconciliation();
            }
            endPoll();
        }
    }

    private SingleScan<T> readSingle(PollStart start, boolean reconcileContent) {
        FileWatcher watcher = start.fileWatcher();
        boolean modified = watcher != null && watcher.checkModifiedEvents();
        String id = baseName(target);
        if (!target.isFile()) {
            return new SingleScan<>(modified, false, null);
        }
        if (!modified && !reconcileContent && !start.retryPending()) {
            return new SingleScan<>(false, true, null);
        }
        return new SingleScan<>(modified, true, readCandidate(id, target));
    }

    private synchronized DocumentDelta applySingle(SingleScan<T> scan, boolean reconcileContent) {
        if (pollInvalidated || pendingDelta != null) {
            return DocumentDelta.EMPTY;
        }
        String id = baseName(target);
        if (!scan.present()) {
            if (scan.modified() || (reconcileContent && documents.containsKey(id))) {
                markDeleted(id);
            }
            List<String> removed = new ArrayList<>();
            applyMatureDeletions(removed);
            return stage(List.of(), removed);
        }
        pendingDeletions.remove(id);
        PreparedLoad<T> prepared = scan.prepared();
        if (prepared == null) {
            return stage(List.of(), List.of());
        }
        if (prepared.ownWrite() || ownerWrites.contains(id)) {
            return DocumentDelta.EMPTY;
        }
        if (!applyPrepared(prepared, true)) {
            return stage(List.of(), List.of());
        }
        return stage(List.of(id), List.of());
    }

    private DocumentDelta stage(List<String> detectedLoaded, List<String> detectedRemoved) {
        List<String> loaded = new ArrayList<>(detectedLoaded);
        List<String> removed = new ArrayList<>(detectedRemoved);
        for (String id : retryIds) {
            if (failedIds.contains(id)) {
                continue;
            }
            if (documents.containsKey(id)) {
                if (!loaded.contains(id)) {
                    loaded.add(id);
                }
                removed.remove(id);
            } else {
                if (!removed.contains(id)) {
                    removed.add(id);
                }
                loaded.remove(id);
            }
        }
        if (loaded.isEmpty() && removed.isEmpty()) {
            return DocumentDelta.EMPTY;
        }
        retryIds.removeAll(loaded);
        retryIds.removeAll(removed);
        pendingDelta = new DocumentDelta(loaded, removed);
        pendingSnapshot = Map.copyOf(documents);
        pendingState = PendingState.READY;
        return pendingDelta;
    }

    private boolean claim(DocumentDelta delta, PendingState requiredState) {
        return delta != DocumentDelta.EMPTY && pendingDelta == delta && pendingState == requiredState;
    }

    private void runDispatched(DocumentDelta delta, Runnable application) {
        synchronized (this) {
            if (!claim(delta, PendingState.QUEUED)) {
                return;
            }
            runApplication(delta, application);
        }
    }

    private boolean runApplication(DocumentDelta delta, Runnable application) {
        pendingState = PendingState.APPLYING;
        try {
            application.run();
            commit(delta);
            return true;
        } catch (ThreadDeath fatal) {
            retry(delta);
            throw fatal;
        } catch (Throwable failure) {
            retry(delta);
            Gloss.logExceptionStack(false, failure, "%s: hot reload apply failed.", kind);
            return false;
        }
    }

    private void commit(DocumentDelta delta) {
        if (pendingDelta != delta) {
            return;
        }
        Map<String, GlossDocument<T>> committed = new HashMap<>(snapshot);
        for (String id : delta.loaded()) {
            GlossDocument<T> document = pendingSnapshot.get(id);
            if (document != null) {
                committed.put(id, document);
            }
        }
        for (String id : delta.removed()) {
            committed.remove(id);
        }
        snapshot = Map.copyOf(committed);
        recordHotload(delta.loaded().size() + delta.removed().size());
        clearPending();
    }

    private void retry(DocumentDelta delta) {
        if (pendingDelta != delta) {
            return;
        }
        retryIds.addAll(delta.loaded());
        retryIds.addAll(delta.removed());
        clearPending();
    }

    private void invalidatePending(String authoritativeId) {
        DocumentDelta delta = pendingDelta;
        if (delta == null) {
            return;
        }
        for (String id : delta.loaded()) {
            if (!id.equals(authoritativeId)) {
                retryIds.add(id);
            }
        }
        for (String id : delta.removed()) {
            if (!id.equals(authoritativeId)) {
                retryIds.add(id);
            }
        }
        clearPending();
    }

    private void clearPending() {
        pendingDelta = null;
        pendingSnapshot = Map.of();
        pendingState = null;
    }

    private void commit(String id, GlossDocument<T> document) {
        Map<String, GlossDocument<T>> committed = new HashMap<>(snapshot);
        if (document == null) {
            committed.remove(id);
        } else {
            committed.put(id, document);
        }
        snapshot = Map.copyOf(committed);
    }

    /**
     * Reads, parses and stores one document. Bytes that match what is already loaded are not a
     * change: the file was rewritten with the content the registry is already serving — an own write
     * read back, or a touch — and re-reporting it would republish and re-apply a document nothing
     * did anything to.
     */
    private boolean load(String id, File file, boolean stabilizeFailure) {
        return applyPrepared(read(id, file), stabilizeFailure);
    }

    /** A watcher-reported candidate: an own write is adopted as-is and never read back. */
    private PreparedLoad<T> readCandidate(String id, File file) {
        return ownWrite.test(file) ? PreparedLoad.ownWrite(id, file) : read(id, file);
    }

    /**
     * Reads and parses one document without touching registry state, so the whole cost can be paid
     * off the monitor. Bytes that match what is already loaded are not a change: the file was
     * rewritten with the content the registry is already serving — an own write read back, or a
     * touch — and re-reporting it would republish and re-apply a document nothing did anything to.
     */
    @SuppressWarnings("removal")
    private PreparedLoad<T> read(String id, File file) {
        String raw = null;
        try {
            long size = Files.size(file.toPath());
            if (size > MAX_DOCUMENT_BYTES) {
                throw new IllegalArgumentException("document exceeds " + MAX_DOCUMENT_BYTES + " bytes");
            }
            raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            GlossDocument<T> current = documents.get(id);
            if (current != null && current.raw().equals(raw)) {
                return PreparedLoad.unchanged(id, file, raw);
            }
            String ignoredSchemaFingerprint = ignoredSchemaFingerprints.get(id);
            if (ignoredSchemaFingerprint != null
                && ignoredSchemaFingerprint.equals(DocumentHashes.sha256(raw))) {
                return PreparedLoad.unchanged(id, file, raw);
            }
            T value = parser.parse(id + EXTENSION, raw);
            if (value == null) {
                throw new IllegalArgumentException("document must not be null");
            }
            return PreparedLoad.parsed(id, file, raw, value);
        } catch (ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            return PreparedLoad.failed(id, file, raw, failure);
        }
    }

    /** Applies one already-read document. The bytes are re-tested against the state as it is now. */
    private boolean applyPrepared(PreparedLoad<T> prepared, boolean stabilizeFailure) {
        String id = prepared.id();
        String raw = prepared.raw();
        Throwable failure = prepared.failure();
        if (failure == null) {
            GlossDocument<T> current = documents.get(id);
            if (current != null && current.raw().equals(raw)) {
                clearFailure(id);
                ignoredSchemaFingerprints.remove(id);
                return false;
            }
            T value = prepared.value();
            if (value == null) {
                return false;
            }
            documents.put(id, GlossDocument.of(id, raw, value, revisionOf.applyAsLong(value)));
            clearFailure(id);
            ignoredSchemaFingerprints.remove(id);
            return true;
        }
        failedIds.add(id);
        GlossDocument<T> committed = snapshot.get(id);
        if (committed == null) {
            documents.remove(id);
        } else {
            documents.put(id, committed);
        }
        if (DocumentEnvelope.isUnsupportedSchemaVersion(failure)) {
            clearFailure(id);
            if (raw != null) {
                ignoredSchemaFingerprints.put(id, DocumentHashes.sha256(raw));
            }
            return false;
        }
        if (stabilizeFailure && raw != null && deferFailure(id, raw)) {
            return false;
        }
        Gloss.logExceptionStack(false, failure, "%s/%s%s: %s", kind, id, EXTENSION, detail(id, failure));
        return false;
    }

    private void markDeleted(String id) {
        if (id == null) {
            return;
        }
        ignoredSchemaFingerprints.remove(id);
        if (documents.containsKey(id)) {
            pendingDeletions.putIfAbsent(id, clock.getAsLong());
        }
    }

    private void applyMatureDeletions(List<String> removed) {
        long now = clock.getAsLong();
        Iterator<Map.Entry<String, Long>> iterator = pendingDeletions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            File file = fileForId(entry.getKey());
            if (file.isFile()) {
                iterator.remove();
                continue;
            }
            if (now - entry.getValue() < DELETION_GRACE_NANOS) {
                continue;
            }
            iterator.remove();
            removeLoaded(entry.getKey(), removed);
        }
    }

    private File fileForId(String id) {
        if (layout == Layout.FILE) {
            return target;
        }
        return new File(target, id.replace('/', File.separatorChar) + EXTENSION);
    }

    private void resetReconciliation() {
        reconciliationFiles = List.of();
        reconciliationIndex = 0;
        reconciliationInProgress = false;
        reconciliationLoaded.clear();
        scheduleReconciliationWindows();
    }

    private void scheduleReconciliationWindows() {
        long now = clock.getAsLong();
        nextFullWatchScanNanos = now + FULL_WATCH_SCAN_WINDOW_NANOS + reconciliationInitialOffsetNanos;
        nextContentReconciliationNanos = now + CONTENT_RECONCILIATION_WINDOW_NANOS
            + reconciliationInitialOffsetNanos;
    }

    static long reconciliationInitialOffsetNanos(String kind) {
        return Math.floorMod(kind.length(), RECONCILIATION_SLOT_COUNT) * RECONCILIATION_SLOT_NANOS;
    }

    private void startContentReconciliation(long now) {
        if (reconciliationInProgress || now < nextContentReconciliationNanos) {
            return;
        }
        List<String> ids = new ArrayList<>(documents.keySet());
        ids.sort(String::compareTo);
        List<File> files = new ArrayList<>(ids.size());
        for (String id : ids) {
            files.add(fileForId(id));
        }
        reconciliationFiles = List.copyOf(files);
        reconciliationIndex = 0;
        reconciliationInProgress = !reconciliationFiles.isEmpty();
        if (!reconciliationInProgress) {
            nextContentReconciliationNanos = clock.getAsLong() + CONTENT_RECONCILIATION_WINDOW_NANOS;
        }
    }

    private void publish() {
        snapshot = Map.copyOf(documents);
    }

    private String idOf(File file) {
        return layout == Layout.TREE ? DocumentTree.idOf(target, file) : baseName(file);
    }

    private boolean isFolderDocument(File file) {
        return isDocument(file) && file.isFile();
    }

    private boolean isDirectChild(File file) {
        File parent = file.getParentFile();
        return parent != null && parent.getAbsolutePath().equals(target.getAbsolutePath());
    }

    private static boolean isDocument(File file) {
        if (file == null) {
            return false;
        }
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(EXTENSION)
            && !name.startsWith(".")
            && !name.startsWith("~")
            && !name.startsWith("#")
            && !name.contains(".tmp.")
            && !name.contains(".temp.");
    }

    private static String baseName(File file) {
        String name = file.getName();
        return name.toLowerCase(Locale.ROOT).endsWith(EXTENSION)
            ? name.substring(0, name.length() - EXTENSION.length())
            : name;
    }

    private String detail(String id, Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isEmpty()) {
            return failure.getClass().getSimpleName();
        }
        String prefix = id + EXTENSION + " ";
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }

    private boolean deferFailure(String id, String raw) {
        String fingerprint = DocumentHashes.sha256(raw);
        if (fingerprint.equals(reportedFailureFingerprints.get(id))) {
            pendingFailureFingerprints.remove(id);
            failureRetryIds.remove(id);
            return true;
        }
        if (!fingerprint.equals(pendingFailureFingerprints.get(id))) {
            pendingFailureFingerprints.put(id, fingerprint);
            failureRetryIds.add(id);
            return true;
        }
        pendingFailureFingerprints.remove(id);
        reportedFailureFingerprints.put(id, fingerprint);
        failureRetryIds.remove(id);
        return false;
    }

    private void clearFailure(String id) {
        pendingFailureFingerprints.remove(id);
        reportedFailureFingerprints.remove(id);
        failureRetryIds.remove(id);
    }

    private void recordHotload(int changes) {
        Gloss current = Gloss.instance;
        if (current != null && current.watchdog() != null) {
            current.watchdog().recordHotload(kind, changes);
        }
    }

    /** Every monitor-guarded decision one pass needs, resolved once when the pass opens. */
    private record PollStart(FolderWatcher folderWatcher, FileWatcher fileWatcher, boolean fullWatchScan,
                             boolean reconcileContent, boolean retryPending, long now) {
    }

    private record WatchScan<V>(boolean changed, List<PreparedLoad<V>> touched,
                                List<DeletedCandidate> deleted) {
    }

    private record SweepPlan(boolean active, List<File> files, int index, Set<String> failed,
                             List<String> retryIds, List<String> failureRetryIds) {
    }

    private record Sweep<V>(List<PreparedLoad<V>> reconciled, int nextIndex,
                            List<PreparedLoad<V>> retries, List<PreparedLoad<V>> failureRetries) {
    }

    private record SingleScan<V>(boolean modified, boolean present, PreparedLoad<V> prepared) {
    }

    /** What the deletion rules need from disk, resolved before the monitor is taken. */
    private record DeletedCandidate(File file, boolean exists, boolean treeDocument, String treePrefix) {
    }

    /** One candidate document read and parsed off the monitor, ready to be applied under it. */
    private record PreparedLoad<V>(String id, File file, boolean missing, boolean ownWrite,
                                   String raw, V value, Throwable failure) {
        private static <V> PreparedLoad<V> missing(String id, File file) {
            return new PreparedLoad<>(id, file, true, false, null, null, null);
        }

        private static <V> PreparedLoad<V> ownWrite(String id, File file) {
            return new PreparedLoad<>(id, file, false, true, null, null, null);
        }

        private static <V> PreparedLoad<V> unchanged(String id, File file, String raw) {
            return new PreparedLoad<>(id, file, false, false, raw, null, null);
        }

        private static <V> PreparedLoad<V> parsed(String id, File file, String raw, V value) {
            return new PreparedLoad<>(id, file, false, false, raw, value, null);
        }

        private static <V> PreparedLoad<V> failed(String id, File file, String raw, Throwable failure) {
            return new PreparedLoad<>(id, file, false, false, raw, null, failure);
        }
    }
}
