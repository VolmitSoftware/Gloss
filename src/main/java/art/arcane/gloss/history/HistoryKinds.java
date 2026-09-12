package art.arcane.gloss.history;

import art.arcane.gloss.editor.sync.EditorSyncDocumentKind;

import java.nio.file.Path;

/**
 * Translates between the data-folder collection an operator types ({@code boards}, {@code tablist})
 * and the kind constant that knows how to place and reload it.
 */
public final class HistoryKinds {
    private static final String EXTENSION = ".json";

    private HistoryKinds() {
    }

    /** The collection name for a kind: its folder, or a singleton file without its extension. */
    public static String collection(EditorSyncDocumentKind kind) {
        String storage = kind.storageName();
        return storage.endsWith(EXTENSION)
                ? storage.substring(0, storage.length() - EXTENSION.length())
                : storage;
    }

    /** The kind stored under {@code collection}, or null when nothing owns that folder. */
    public static EditorSyncDocumentKind byCollection(String collection) {
        if (collection == null || collection.isBlank()) {
            return null;
        }
        EditorSyncDocumentKind folder = EditorSyncDocumentKind.byStorageCollection(collection);
        return folder != null
                ? folder
                : EditorSyncDocumentKind.byStorageCollection(collection + EXTENSION);
    }

    public static EditorSyncDocumentKind requireCollection(String collection) {
        EditorSyncDocumentKind kind = byCollection(collection);
        if (kind == null) {
            throw new IllegalArgumentException("unknown document kind: " + collection);
        }
        return kind;
    }

    /** The data-folder file a document lives in, or null when the path names no document kind. */
    public static DocumentPath resolve(Path dataDirectory, String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        int separator = normalized.indexOf('/');
        String collection = separator < 0 ? normalized : normalized.substring(0, separator);
        EditorSyncDocumentKind kind = byCollection(
                collection.endsWith(EXTENSION)
                        ? collection.substring(0, collection.length() - EXTENSION.length())
                        : collection);
        if (kind == null || !normalized.endsWith(EXTENSION)) {
            return null;
        }
        String id = switch (kind.layout()) {
            case SINGLE -> kind.singletonId();
            default -> separator < 0
                    ? null
                    : normalized.substring(separator + 1, normalized.length() - EXTENSION.length());
        };
        if (id == null || id.isBlank()) {
            return null;
        }
        return new DocumentPath(kind, collection(kind), id, dataDirectory.resolve(normalized));
    }

    public record DocumentPath(EditorSyncDocumentKind kind, String collection, String id, Path file) {
    }
}
