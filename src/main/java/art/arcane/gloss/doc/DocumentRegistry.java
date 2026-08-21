package art.arcane.gloss.doc;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.io.FileWatcher;
import art.arcane.volmlib.util.io.FolderWatcher;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
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
    private static final long MAX_DOCUMENT_BYTES = 2L * 1024L * 1024L;
    private static final long RECONCILIATION_BYTE_BUDGET = 8L * 1024L * 1024L;
    private static final int RECONCILIATION_FILE_BUDGET = 256;
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
    private final Set<String> retryIds;
    private final Set<String> failedIds;
    private volatile Map<String, GlossDocument<T>> snapshot;
    private volatile FolderWatcher folderWatcher;
    private volatile FileWatcher fileWatcher;
    private List<File> reconciliationFiles;
    private int reconciliationIndex;
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
        this.retryIds = new HashSet<>();
        this.failedIds = new HashSet<>();
        this.snapshot = Map.of();
        this.reconciliationFiles = List.of();
        this.pendingSnapshot = Map.of();
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
        clearPending();
        retryIds.clear();
        failedIds.clear();
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
            load(id, file);
        }
        documents.keySet().retainAll(present);
        replaceFolderWatcher(new FolderWatcher(target));
        publish();
    }

    public synchronized DocumentDelta poll() {
        if (pendingDelta != null) {
            return DocumentDelta.EMPTY;
        }
        failedIds.clear();
        if (layout == Layout.FILE) {
            return pollSingle();
        }
        FolderWatcher watcher = folderWatcher;
        List<String> loaded = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        if (watcher != null && watcher.checkModified()) {
            for (File file : watcher.getChanged()) {
                loadTouched(file, loaded, false);
            }
            for (File file : watcher.getCreated()) {
                loadTouched(file, loaded, true);
            }
            for (File file : watcher.getDeleted()) {
                queueDeleted(file);
            }
        }
        reconcileContent(loaded);
        applyMatureDeletions(removed);
        return stage(loaded, removed);
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
        return present;
    }

    /**
     * A watcher reports a new subdirectory as one creation, so a creation in a tree is walked to
     * reach the files it arrived with. A change is not: the watcher reports the directory whose
     * contents moved as well as the file that moved, and walking it would re-read the whole subtree
     * for every edit inside it. A flat folder only ever sees its own children.
     */
    private void loadTouched(File file, List<String> loaded, boolean walk) {
        if (layout == Layout.TREE) {
            if (walk) {
                for (File document : DocumentTree.discover(target, file)) {
                    acceptTouched(document, loaded);
                }
            } else if (DocumentTree.isDocument(target, file)) {
                acceptTouched(file, loaded);
            }
            return;
        }
        if (!isFolderDocument(file) || !isDirectChild(file)) {
            return;
        }
        acceptTouched(file, loaded);
    }

    private void acceptTouched(File file, List<String> loaded) {
        String id = idOf(file);
        pendingDeletions.remove(id);
        if (ownWrite.test(file)) {
            return;
        }
        if (load(id, file) && !loaded.contains(id)) {
            loaded.add(id);
        }
    }

    private void queueDeleted(File file) {
        if (file != null && file.exists()) {
            if (isDocument(file)) {
                pendingDeletions.remove(idOf(file));
            }
            return;
        }
        if (layout == Layout.TREE) {
            if (DocumentTree.isDocument(target, file)) {
                markDeleted(DocumentTree.idOf(target, file));
            }
            String prefix = DocumentTree.prefixOf(target, file);
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
            removed.add(id);
        }
    }

    private void reloadSingle() {
        if (target.isFile()) {
            load(baseName(target), target);
        } else {
            documents.remove(baseName(target));
        }
        replaceFileWatcher(new FileWatcher(target));
        publish();
    }

    @Override
    public synchronized void close() {
        replaceFolderWatcher(null);
        replaceFileWatcher(null);
        clearPending();
        retryIds.clear();
        failedIds.clear();
        pendingDeletions.clear();
        resetReconciliation();
    }

    private void replaceFolderWatcher(FolderWatcher replacement) {
        FolderWatcher previous = folderWatcher;
        folderWatcher = replacement;
        if (previous != null) {
            previous.close();
        }
    }

    private void replaceFileWatcher(FileWatcher replacement) {
        FileWatcher previous = fileWatcher;
        fileWatcher = replacement;
        if (previous != null) {
            previous.close();
        }
    }

    private DocumentDelta pollSingle() {
        FileWatcher watcher = fileWatcher;
        boolean modified = watcher != null && watcher.checkModified();
        String id = baseName(target);
        if (!target.isFile()) {
            if (modified || documents.containsKey(id)) {
                markDeleted(id);
            }
            List<String> removed = new ArrayList<>();
            applyMatureDeletions(removed);
            return stage(List.of(), removed);
        }
        pendingDeletions.remove(id);
        if (ownWrite.test(target)) {
            return DocumentDelta.EMPTY;
        }
        if (!load(id, target)) {
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
    @SuppressWarnings("removal")
    private boolean load(String id, File file) {
        try {
            long size = Files.size(file.toPath());
            if (size > MAX_DOCUMENT_BYTES) {
                throw new IllegalArgumentException("document exceeds " + MAX_DOCUMENT_BYTES + " bytes");
            }
            String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            GlossDocument<T> current = documents.get(id);
            if (current != null && current.raw().equals(raw)) {
                return false;
            }
            T value = parser.parse(id + EXTENSION, raw);
            if (value == null) {
                throw new IllegalArgumentException("document must not be null");
            }
            documents.put(id, GlossDocument.of(id, raw, value, revisionOf.applyAsLong(value)));
            return true;
        } catch (ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            failedIds.add(id);
            GlossDocument<T> committed = snapshot.get(id);
            if (committed == null) {
                documents.remove(id);
            } else {
                documents.put(id, committed);
            }
            Gloss.logExceptionStack(false, failure, "%s/%s%s: %s", kind, id, EXTENSION, detail(id, failure));
            return false;
        }
    }

    private void reconcileContent(List<String> loaded) {
        if (reconciliationIndex >= reconciliationFiles.size()) {
            reconciliationFiles = currentFiles();
            reconciliationIndex = 0;
        }
        long bytes = 0L;
        int files = 0;
        while (reconciliationIndex < reconciliationFiles.size() && files < RECONCILIATION_FILE_BUDGET) {
            File file = reconciliationFiles.get(reconciliationIndex);
            long size = file.isFile() ? Math.max(0L, file.length()) : 0L;
            if (files > 0 && bytes + Math.min(size, MAX_DOCUMENT_BYTES) > RECONCILIATION_BYTE_BUDGET) {
                break;
            }
            reconciliationIndex++;
            files++;
            bytes += Math.min(size, MAX_DOCUMENT_BYTES);
            if (file.isFile()) {
                acceptTouched(file, loaded);
            }
        }
    }

    private void markDeleted(String id) {
        if (id != null && documents.containsKey(id)) {
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
}
