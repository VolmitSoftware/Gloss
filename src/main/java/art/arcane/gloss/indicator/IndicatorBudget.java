package art.arcane.gloss.indicator;

final class IndicatorBudget {
    private final long windowMs;

    private long[] ring = new long[0];
    private int write;
    private int filled;

    IndicatorBudget(long windowMs) {
        this.windowMs = Math.max(1L, windowMs);
    }

    synchronized boolean saturated(long nowMs, int limit) {
        int capacity = Math.max(1, limit);
        if (ring.length != capacity || filled < capacity) {
            return false;
        }
        return nowMs - ring[write] < windowMs;
    }

    synchronized void record(long nowMs, int limit) {
        int capacity = Math.max(1, limit);
        if (ring.length != capacity) {
            ring = new long[capacity];
            write = 0;
            filled = 0;
        }
        ring[write] = nowMs;
        write = (write + 1) % capacity;
        if (filled < capacity) {
            filled++;
        }
    }
}
