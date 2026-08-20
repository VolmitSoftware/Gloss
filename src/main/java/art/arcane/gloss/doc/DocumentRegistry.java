package art.arcane.gloss.doc;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.io.FileWatcher;
import art.arcane.volmlib.util.io.FolderWatcher;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.logging.Level;

public final class DocumentRegistry<T> {
    private static final String EXTENSION = ".json";

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
    private final Map<String, GlossDocument<T>> documents;
    private volatile Map<String, GlossDocument<T>> snapshot;
    private volatile FolderWatcher folderWatcher;
    private volatile FileWatcher fileWatcher;

    private DocumentRegistry(String kind, File target, Layout layout, DocumentParser<T> parser,
                             ToLongFunction<T> revisionOf, Predicate<File> ownWrite) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.target = Objects.requireNonNull(target, "target");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.revisionOf = Objects.requireNonNull(revisionOf, "revisionOf");
        this.ownWrite = Objects.requireNonNull(ownWrite, "ownWrite");
        this.documents = new ConcurrentHashMap<>();
        this.snapshot = Map.of();
    }

    public static <T> DocumentRegistry<T> folder(String kind, File folder, DocumentParser<T> parser,
                                                 ToLongFunction<T> revisionOf) {
        return folder(kind, folder, parser, revisionOf, file -> false);
    }

    public static <T> DocumentRegistry<T> folder(String kind, File folder, DocumentParser<T> parser,
                                                 ToLongFunction<T> revisionOf, Predicate<File> ownWrite) {
        return new DocumentRegistry<>(kind, folder, Layout.FOLDER, parser, revisionOf, ownWrite);
    }

    /**
     * A folder whose subdirectories are part of the id: {@code menus/archive/old.json} is the
     * document {@code archive/old}. {@link DocumentTree} owns the path rules.
     */
    public static <T> DocumentRegistry<T> folderTree(String kind, File folder, DocumentParser<T> parser,
                                                     ToLongFunction<T> revisionOf) {
        return new DocumentRegistry<>(kind, folder, Layout.TREE, parser, revisionOf, file -> false);
    }

    public static <T> DocumentRegistry<T> singleFile(String kind, File file, DocumentParser<T> parser,
                                                     ToLongFunction<T> revisionOf) {
        return new DocumentRegistry<>(kind, file, Layout.FILE, parser, revisionOf, target -> false);
    }

    public String kind() {
        return kind;
    }

    public Map<String, GlossDocument<T>> snapshot() {
        return snapshot;
    }

    public GlossDocument<T> get(String id) {
        return id == null ? null : snapshot.get(id);
    }

    public Set<String> ids() {
        return Set.copyOf(snapshot.keySet());
    }

    /**
     * Adopts a document the owner just wrote, without waiting for the watcher to read it back. The
     * next poll finds the same bytes on disk and reports nothing, so a write publishes exactly once.
     */
    public GlossDocument<T> publish(String id, String raw, T value) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(value, "value");
        GlossDocument<T> document = GlossDocument.of(id, raw, value, revisionOf.applyAsLong(value));
        documents.put(id, document);
        publish();
        return document;
    }

    /** Drops a document the owner just deleted or rolled back. */
    public boolean remove(String id) {
        if (id == null || documents.remove(id) == null) {
            return false;
        }
        publish();
        return true;
    }

    /**
     * Loads every document currently on disk. A missing folder is an empty registry, never a folder
     * Gloss creates: the folder appears when something writes into it, and {@link FolderWatcher}
     * reports its contents as creations on the poll after that.
     */
    public void reload() {
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
        folderWatcher = new FolderWatcher(target);
        publish();
    }

    public DocumentDelta poll() {
        if (layout == Layout.FILE) {
            return pollSingle();
        }
        FolderWatcher watcher = folderWatcher;
        if (watcher == null || !watcher.checkModified()) {
            return DocumentDelta.EMPTY;
        }
        List<String> loaded = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (File file : watcher.getChanged()) {
            loadTouched(file, loaded, false);
        }
        for (File file : watcher.getCreated()) {
            loadTouched(file, loaded, true);
        }
        for (File file : watcher.getDeleted()) {
            unloadDeleted(file, removed);
        }
        if (loaded.isEmpty() && removed.isEmpty()) {
            return DocumentDelta.EMPTY;
        }
        publish();
        return new DocumentDelta(loaded, removed);
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
        if (ownWrite.test(file)) {
            return;
        }
        String id = idOf(file);
        if (load(id, file)) {
            loaded.add(id);
        }
    }

    private void unloadDeleted(File file, List<String> removed) {
        if (layout == Layout.TREE) {
            if (DocumentTree.isDocument(target, file)) {
                removeLoaded(DocumentTree.idOf(target, file), removed);
            }
            String prefix = DocumentTree.prefixOf(target, file);
            if (prefix == null) {
                return;
            }
            String nested = prefix + "/";
            for (String id : List.copyOf(documents.keySet())) {
                if (id.startsWith(nested)) {
                    removeLoaded(id, removed);
                }
            }
            return;
        }
        if (isDocument(file) && isDirectChild(file)) {
            removeLoaded(baseName(file), removed);
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
        fileWatcher = new FileWatcher(target);
        publish();
    }

    private DocumentDelta pollSingle() {
        FileWatcher watcher = fileWatcher;
        if (watcher == null || !watcher.checkModified()) {
            return DocumentDelta.EMPTY;
        }
        if (ownWrite.test(target)) {
            return DocumentDelta.EMPTY;
        }
        String id = baseName(target);
        if (!target.isFile()) {
            if (documents.remove(id) == null) {
                return DocumentDelta.EMPTY;
            }
            publish();
            return new DocumentDelta(List.of(), List.of(id));
        }
        if (!load(id, target)) {
            return DocumentDelta.EMPTY;
        }
        publish();
        return new DocumentDelta(List.of(id), List.of());
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
            Gloss.log(Level.WARNING, "%s/%s%s: %s", kind, id, EXTENSION, detail(id, failure));
            return false;
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
        return file != null && file.getName().toLowerCase(Locale.ROOT).endsWith(EXTENSION);
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
