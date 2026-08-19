package art.arcane.gloss.integrate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MetricReferences {
    private final int capacity;
    private final long windowMs;
    private final Map<String, Long> lastRequested;

    public MetricReferences(int capacity, long windowMs) {
        this.capacity = Math.max(1, capacity);
        this.windowMs = Math.max(1L, windowMs);
        this.lastRequested = new ConcurrentHashMap<>();
    }

    public void reference(String key, long nowMs) {
        if (key == null || key.isBlank()) {
            return;
        }
        lastRequested.put(key, nowMs);
    }

    public void referenceAll(Set<String> keys, long nowMs) {
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            reference(key, nowMs);
        }
    }

    public Set<String> active(long nowMs) {
        List<Map.Entry<String, Long>> live = new ArrayList<>(lastRequested.size());
        for (Map.Entry<String, Long> entry : lastRequested.entrySet()) {
            if (nowMs - entry.getValue() > windowMs) {
                lastRequested.remove(entry.getKey(), entry.getValue());
                continue;
            }
            live.add(entry);
        }

        if (live.size() > capacity) {
            live.sort(Comparator.comparingLong((Map.Entry<String, Long> entry) -> entry.getValue()).reversed());
            for (int index = capacity; index < live.size(); index++) {
                Map.Entry<String, Long> expired = live.get(index);
                lastRequested.remove(expired.getKey(), expired.getValue());
            }
            live = live.subList(0, capacity);
        }

        Set<String> keys = new HashSet<>(live.size());
        for (Map.Entry<String, Long> entry : live) {
            keys.add(entry.getKey());
        }
        return Set.copyOf(keys);
    }

    public int tracked() {
        return lastRequested.size();
    }

    public void clear() {
        lastRequested.clear();
    }
}
