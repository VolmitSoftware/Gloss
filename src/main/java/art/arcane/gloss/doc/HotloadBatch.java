package art.arcane.gloss.doc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

final class HotloadBatch {
    private final Map<String, Integer> changesByKind = new TreeMap<>();

    synchronized void record(String kind, int changes) {
        if (kind == null || kind.isBlank() || changes <= 0) {
            return;
        }
        changesByKind.merge(kind, changes, Integer::sum);
    }

    synchronized Snapshot drain() {
        if (changesByKind.isEmpty()) {
            return Snapshot.EMPTY;
        }
        Map<String, Integer> changes = Collections.unmodifiableMap(new LinkedHashMap<>(changesByKind));
        changesByKind.clear();
        int total = 0;
        for (int count : changes.values()) {
            total += count;
        }
        return new Snapshot(changes, total);
    }

    synchronized void clear() {
        changesByKind.clear();
    }

    record Snapshot(Map<String, Integer> changesByKind, int totalChanges) {
        private static final Snapshot EMPTY = new Snapshot(Map.of(), 0);

        boolean isEmpty() {
            return totalChanges <= 0;
        }
    }
}
