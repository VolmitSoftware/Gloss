package art.arcane.gloss.indicator;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Non consuming pre-gate for the damage path. Every damage event on every region thread probes it,
 * so the window is an atomic ring rather than a monitor those threads would queue on.
 */
final class IndicatorBudget {
    private final long windowMs;

    private volatile Window window;

    IndicatorBudget(long windowMs) {
        this.windowMs = Math.max(1L, windowMs);
    }

    boolean saturated(long nowMs, int limit) {
        Window current = window;
        int capacity = Math.max(1, limit);
        if (current == null || current.capacity != capacity) {
            return false;
        }
        long written = current.written.get();
        if (written < capacity) {
            return false;
        }
        return nowMs - current.stamps.get((int) (written % capacity)) < windowMs;
    }

    void record(long nowMs, int limit) {
        int capacity = Math.max(1, limit);
        Window current = window;
        if (current == null || current.capacity != capacity) {
            current = new Window(capacity);
            window = current;
        }
        current.stamps.set((int) (current.written.getAndIncrement() % capacity), nowMs);
    }

    private static final class Window {
        private final int capacity;
        private final AtomicLongArray stamps;
        private final AtomicLong written = new AtomicLong();

        private Window(int capacity) {
            this.capacity = capacity;
            this.stamps = new AtomicLongArray(capacity);
        }
    }
}
