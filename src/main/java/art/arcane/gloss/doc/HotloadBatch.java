package art.arcane.gloss.doc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

final class HotloadBatch {
    private final Map<String, Integer> changesByKind = new TreeMap<>();
    private final Map<String, Integer> skippedByKind = new TreeMap<>();

    synchronized void record(String kind, int changes) {
        if (kind == null || kind.isBlank() || changes <= 0) {
            return;
        }
        changesByKind.merge(kind, changes, Integer::sum);
    }

    /** Files a poll refused for their {@code schemaVersion}; they ride the same notice. */
    synchronized void recordSkipped(String kind, int skipped) {
        if (kind == null || kind.isBlank() || skipped <= 0) {
            return;
        }
        skippedByKind.merge(kind, skipped, Integer::sum);
    }

    synchronized Snapshot drain() {
        if (changesByKind.isEmpty() && skippedByKind.isEmpty()) {
            return Snapshot.EMPTY;
        }
        Map<String, Integer> changes = Collections.unmodifiableMap(new LinkedHashMap<>(changesByKind));
        Map<String, Integer> skipped = Collections.unmodifiableMap(new LinkedHashMap<>(skippedByKind));
        changesByKind.clear();
        skippedByKind.clear();
        return new Snapshot(changes, total(changes), skipped, total(skipped));
    }

    synchronized void clear() {
        changesByKind.clear();
        skippedByKind.clear();
    }

    private static int total(Map<String, Integer> counts) {
        int total = 0;
        for (int count : counts.values()) {
            total += count;
        }
        return total;
    }

    record Snapshot(Map<String, Integer> changesByKind, int totalChanges,
                    Map<String, Integer> skippedByKind, int totalSkipped) {
        private static final Snapshot EMPTY = new Snapshot(Map.of(), 0, Map.of(), 0);

        boolean isEmpty() {
            return totalChanges <= 0 && totalSkipped <= 0;
        }
    }
}
