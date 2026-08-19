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

    private final String kind;
    private final File target;
    private final boolean singleFile;
    private final DocumentParser<T> parser;
    private final ToLongFunction<T> revisionOf;
    private final Predicate<File> ownWrite;
    private final Map<String, GlossDocument<T>> documents;
    private volatile Map<String, GlossDocument<T>> snapshot;
    private volatile FolderWatcher folderWatcher;
    private volatile FileWatcher fileWatcher;

    private DocumentRegistry(String kind, File target, boolean singleFile, DocumentParser<T> parser,
                             ToLongFunction<T> revisionOf, Predicate<File> ownWrite) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.target = Objects.requireNonNull(target, "target");
        this.singleFile = singleFile;
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
        return new DocumentRegistry<>(kind, folder, false, parser, revisionOf, ownWrite);
    }

    public static <T> DocumentRegistry<T> singleFile(String kind, File file, DocumentParser<T> parser,
                                                     ToLongFunction<T> revisionOf) {
        return new DocumentRegistry<>(kind, file, true, parser, revisionOf, target -> false);
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

    public void reload() {
        if (singleFile) {
            reloadSingle();
            return;
        }
        if (!target.isDirectory() && !target.mkdirs()) {
            Gloss.log(Level.WARNING, "%s: unable to create folder at %s", kind, target.getAbsolutePath());
        }
        Set<String> present = new HashSet<>();
        File[] files = target.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!isDocument(file) || !file.isFile()) {
                    continue;
                }
                present.add(baseName(file));
                load(file);
            }
        }
        documents.keySet().retainAll(present);
        folderWatcher = new FolderWatcher(target);
        publish();
    }

    public DocumentDelta poll() {
        if (singleFile) {
            return pollSingle();
        }
        FolderWatcher watcher = folderWatcher;
        if (watcher == null || !watcher.checkModified()) {
            return DocumentDelta.EMPTY;
        }
        List<String> loaded = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<File> touched = new ArrayList<>(watcher.getChanged());
        touched.addAll(watcher.getCreated());
        for (File file : touched) {
            if (!isDocument(file) || !file.isFile() || !isDirectChild(file) || ownWrite.test(file)) {
                continue;
            }
            if (load(file)) {
                loaded.add(baseName(file));
            }
        }
        for (File file : watcher.getDeleted()) {
            if (!isDocument(file) || !isDirectChild(file)) {
                continue;
            }
            String id = baseName(file);
            if (documents.remove(id) != null) {
                removed.add(id);
            }
        }
        if (loaded.isEmpty() && removed.isEmpty()) {
            return DocumentDelta.EMPTY;
        }
        publish();
        return new DocumentDelta(loaded, removed);
    }

    private void reloadSingle() {
        if (target.isFile()) {
            load(target);
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
        if (!load(target)) {
            return DocumentDelta.EMPTY;
        }
        publish();
        return new DocumentDelta(List.of(id), List.of());
    }

    @SuppressWarnings("removal")
    private boolean load(File file) {
        String id = baseName(file);
        try {
            String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
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
