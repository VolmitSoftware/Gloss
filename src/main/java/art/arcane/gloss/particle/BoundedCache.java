package art.arcane.gloss.particle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Bounded memo for the per tick particle work. Two generations instead of an LRU list so the read
 * path stays lock free on the region threads; the cold generation is dropped whole once the hot one
 * fills, which keeps the keys currently on screen warm without ever growing past the bound.
 */
final class BoundedCache<K, V> {
    private final int generationSize;

    private volatile Map<K, V> hot = new ConcurrentHashMap<>();
    private volatile Map<K, V> cold = Map.of();

    BoundedCache(int maxEntries) {
        this.generationSize = Math.max(1, maxEntries / 2);
    }

    V get(K key, Function<K, V> factory) {
        Map<K, V> current = hot;
        V value = current.get(key);
        if (value != null) {
            return value;
        }
        value = cold.get(key);
        if (value == null) {
            value = factory.apply(key);
        }
        if (current.size() >= generationSize) {
            rotate(current);
            current = hot;
        }
        V raced = current.putIfAbsent(key, value);
        return raced == null ? value : raced;
    }

    int size() {
        return hot.size() + cold.size();
    }

    void clear() {
        hot = new ConcurrentHashMap<>();
        cold = Map.of();
    }

    private synchronized void rotate(Map<K, V> full) {
        if (hot != full) {
            return;
        }
        cold = full;
        hot = new ConcurrentHashMap<>();
    }
}
