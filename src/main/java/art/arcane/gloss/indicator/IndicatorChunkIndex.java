package art.arcane.gloss.indicator;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Chunk buckets for the live indicators. A viewer crossing a chunk boundary only has to visit the
 * indicators anchored near it instead of every live indicator on the server.
 */
final class IndicatorChunkIndex<T> {
    private final Map<UUID, Map<Long, Set<T>>> worlds = new ConcurrentHashMap<>();
    private final Set<T> live = ConcurrentHashMap.newKeySet();

    /** Widest radius seen since the last clear; a stale high value only widens an empty scan. */
    private volatile int scanRadius = 1;

    static int chunkRadius(double range) {
        double safe = Double.isFinite(range) ? Math.max(0.0D, range) : 0.0D;
        return (int) Math.floor(safe / 16.0D) + 1;
    }

    void add(UUID worldId, int chunkX, int chunkZ, int chunkRadius, T value) {
        if (worldId == null || value == null || !live.add(value)) {
            return;
        }
        worlds.computeIfAbsent(worldId, id -> new ConcurrentHashMap<>())
            .compute(key(chunkX, chunkZ), (cell, bucket) -> {
                Set<T> target = bucket == null ? ConcurrentHashMap.newKeySet() : bucket;
                target.add(value);
                return target;
            });
        if (chunkRadius > scanRadius) {
            scanRadius = chunkRadius;
        }
    }

    void remove(UUID worldId, int chunkX, int chunkZ, T value) {
        if (worldId == null || value == null || !live.remove(value)) {
            return;
        }
        Map<Long, Set<T>> cells = worlds.get(worldId);
        if (cells == null) {
            return;
        }
        cells.computeIfPresent(key(chunkX, chunkZ), (cell, bucket) -> {
            bucket.remove(value);
            return bucket.isEmpty() ? null : bucket;
        });
    }

    void forEachNear(UUID worldId, int chunkX, int chunkZ, Consumer<T> action) {
        if (worldId == null) {
            return;
        }
        Map<Long, Set<T>> cells = worlds.get(worldId);
        if (cells == null || cells.isEmpty()) {
            return;
        }
        int radius = scanRadius;
        for (int x = chunkX - radius; x <= chunkX + radius; x++) {
            for (int z = chunkZ - radius; z <= chunkZ + radius; z++) {
                Set<T> bucket = cells.get(key(x, z));
                if (bucket == null) {
                    continue;
                }
                for (T value : bucket) {
                    action.accept(value);
                }
            }
        }
    }

    boolean isEmpty() {
        return live.isEmpty();
    }

    void clear() {
        worlds.clear();
        live.clear();
        scanRadius = 1;
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
