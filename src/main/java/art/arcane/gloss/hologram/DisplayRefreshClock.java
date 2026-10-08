package art.arcane.gloss.hologram;

final class DisplayRefreshClock {
    private volatile long nextTick = Long.MIN_VALUE;

    boolean due(long tick, Integer interval) {
        if (interval == null) {
            return true;
        }
        if (tick < nextTick) {
            return false;
        }
        nextTick = tick + interval;
        return true;
    }

    void reset() {
        nextTick = Long.MIN_VALUE;
    }
}
