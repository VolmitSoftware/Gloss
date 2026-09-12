package art.arcane.gloss.history;

import art.arcane.gloss.persistence.GlossProjectTransaction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The editor-sync backup journals read as history. Every publication that replaced a document kept
 * the bytes it replaced, so those copies belong on the same timeline as the watchdog's.
 */
public final class TransactionRecords {
    private static final Path RELATIVE_ROOT = Path.of("");

    private TransactionRecords() {
    }

    public static List<HistoryEntry> of(List<GlossProjectTransaction.TransactionRecord> records) {
        List<HistoryEntry> entries = new ArrayList<>();
        for (GlossProjectTransaction.TransactionRecord record : records) {
            for (GlossProjectTransaction.TransactionFile file : record.files()) {
                HistoryKinds.DocumentPath document =
                        HistoryKinds.resolve(RELATIVE_ROOT, file.relativePath());
                if (document == null) {
                    continue;
                }
                entries.add(new HistoryEntry(document.collection(), document.id(),
                        record.epochMillis(), "editor:" + record.label(), file.bytes(),
                        file.backupFile()));
            }
        }
        entries.sort(Comparator.comparingLong(HistoryEntry::epochMillis).reversed()
                .thenComparing(HistoryEntry::id));
        return List.copyOf(entries);
    }

    public static List<HistoryEntry> of(List<GlossProjectTransaction.TransactionRecord> records,
                                        String kind, String id) {
        List<HistoryEntry> matched = new ArrayList<>();
        for (HistoryEntry entry : of(records)) {
            if (entry.kind().equals(kind) && entry.id().equals(id)) {
                matched.add(entry);
            }
        }
        return List.copyOf(matched);
    }
}
