package art.arcane.gloss.particle;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedCacheTest {
    @Test
    void aWarmKeyIsComputedOnlyOnce() {
        BoundedCache<String, Object> cache = new BoundedCache<>(8);
        AtomicInteger computed = new AtomicInteger();

        Object first = cache.get("a", key -> {
            computed.incrementAndGet();
            return new Object();
        });
        Object second = cache.get("a", key -> {
            computed.incrementAndGet();
            return new Object();
        });

        assertSame(first, second);
        assertEquals(1, computed.get());
    }

    /** The old sample cache froze forever at its ceiling; a full cache must keep admitting. */
    @Test
    void aFullCacheKeepsAdmittingNewKeys() {
        BoundedCache<Integer, String> cache = new BoundedCache<>(8);
        for (int key = 0; key < 200; key++) {
            cache.get(key, value -> "v" + value);
        }
        AtomicInteger computed = new AtomicInteger();

        cache.get(1000, key -> {
            computed.incrementAndGet();
            return "late";
        });
        String repeated = cache.get(1000, key -> "recomputed");

        assertEquals(1, computed.get());
        assertEquals("late", repeated, "a freshly admitted key must stay cached");
    }

    @Test
    void theCacheNeverGrowsPastItsBound() {
        BoundedCache<Integer, String> cache = new BoundedCache<>(16);
        for (int key = 0; key < 5000; key++) {
            cache.get(key, value -> "v" + value);
        }

        assertTrue(cache.size() <= 16, "cache held " + cache.size() + " entries");
    }

    @Test
    void recentKeysSurviveTheEviction() {
        BoundedCache<Integer, String> cache = new BoundedCache<>(16);
        for (int key = 0; key < 100; key++) {
            cache.get(key, value -> "v" + value);
        }
        AtomicInteger recomputed = new AtomicInteger();

        cache.get(99, key -> {
            recomputed.incrementAndGet();
            return "v99";
        });

        assertEquals(0, recomputed.get(), "the most recent key must still be warm");
    }

    @Test
    void clearDropsEveryGeneration() {
        BoundedCache<Integer, String> cache = new BoundedCache<>(8);
        for (int key = 0; key < 20; key++) {
            cache.get(key, value -> "v" + value);
        }

        cache.clear();

        assertEquals(0, cache.size());
    }
}
