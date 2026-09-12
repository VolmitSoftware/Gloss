package art.arcane.gloss.history;

import art.arcane.gloss.editor.sync.EditorSyncDocumentKind;
import art.arcane.gloss.persistence.GlossProjectTransaction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Writes a stored version back over the live document. The copy being replaced is recorded first,
 * so a restore is itself undoable, and the write goes through the project transaction that every
 * other data-folder write uses.
 */
final class HistoryRestore {
    private final Path dataDirectory;
    private final GlossProjectTransaction transaction;
    private final HistoryStore store;

    HistoryRestore(Path dataDirectory, GlossProjectTransaction transaction, HistoryStore store) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath().normalize();
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.store = Objects.requireNonNull(store, "store");
    }

    EditorSyncDocumentKind apply(String kind, String id, HistoryEntry entry) throws IOException {
        EditorSyncDocumentKind documentKind = HistoryKinds.requireCollection(kind);
        byte[] content = store.read(Objects.requireNonNull(entry, "entry"));
        Path target = documentKind.path(dataDirectory, id);
        byte[] current = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                ? Files.readAllBytes(target)
                : null;
        if (current != null) {
            store.record(kind, id, current, "restore");
        }
        GlossProjectTransaction.Pending pending = transaction.apply("restore-" + kind + "-" + id,
                Map.of(target, GlossProjectTransaction.Mutation.write(content)),
                current == null ? Map.of() : Map.of(target, current));
        transaction.commit(pending);
        return documentKind;
    }
}
