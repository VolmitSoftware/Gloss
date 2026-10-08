package art.arcane.gloss.integrate;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class MetricReferences {
    private int capacity;
    private long windowMs;
    private long evictions;
    private final Map<String, Long> lastRequested = new LinkedHashMap<>(16, 0.75F, true);

    public MetricReferences(int capacity, long windowMs) {
        configure(capacity, windowMs);
    }

    public synchronized void configure(int capacity, long windowMs) {
        this.capacity = Math.max(1, capacity);
        this.windowMs = Math.max(1L, windowMs);
        while (lastRequested.size() > this.capacity) {
            evictOldest();
        }
    }

    public synchronized void reference(String key, long nowMs) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (!lastRequested.containsKey(key) && lastRequested.size() >= capacity) {
            evictOldest();
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

    public synchronized Set<String> active(long nowMs) {
        lastRequested.entrySet().removeIf(entry -> nowMs - entry.getValue() > windowMs);
        return Set.copyOf(lastRequested.keySet());
    }

    public synchronized int tracked() {
        return lastRequested.size();
    }

    public synchronized long evictions() {
        return evictions;
    }

    public synchronized void clear() {
        lastRequested.clear();
        evictions = 0;
    }

    private void evictOldest() {
        Iterator<String> oldest = lastRequested.keySet().iterator();
        oldest.next();
        oldest.remove();
        evictions++;
    }
}
