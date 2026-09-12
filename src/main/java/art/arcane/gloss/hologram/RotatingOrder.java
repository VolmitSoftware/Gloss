package art.arcane.gloss.hologram;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class RotatingOrder<K> {
    private final Object lock = new Object();
    private final K[] empty;
    private volatile K[] order;
    private volatile long orderGeneration = -1L;
    private int startOffset;

    public RotatingOrder(K[] empty) {
        this.empty = empty;
        this.order = empty;
    }

    public K[] order(Map<K, ?> source, AtomicLong membershipGeneration) {
        long generation = membershipGeneration.get();
        K[] cached = order;
        if (orderGeneration == generation) {
            return cached;
        }
        synchronized (lock) {
            generation = membershipGeneration.get();
            if (orderGeneration != generation) {
                cached = source.keySet().toArray(empty);
                order = cached;
                orderGeneration = generation;
                startOffset = cached.length == 0 ? 0 : Math.floorMod(startOffset, cached.length);
            }
            return order;
        }
    }

    public int start(K[] order) {
        return order.length == 0 ? 0 : Math.floorMod(startOffset, order.length);
    }

    public void advance(int start, int visited, int length) {
        if (length > 0 && visited > 0) {
            startOffset = (start + visited) % length;
        }
    }
}
