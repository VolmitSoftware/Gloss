package art.arcane.gloss.zone;

/**
 * One viewer's particle allowance for a tick, shared by every zone that viewer can see so four
 * overlapping zones cost the same as one.
 */
public final class ZoneBudget {
    private final int capacity;
    private int remaining;

    public ZoneBudget(int capacity) {
        this.capacity = Math.max(0, capacity);
        this.remaining = this.capacity;
    }

    public int take(int requested) {
        int granted = Math.max(0, Math.min(requested, remaining));
        remaining -= granted;
        return granted;
    }

    public void reset() {
        remaining = capacity;
    }

    public int remaining() {
        return remaining;
    }
}
