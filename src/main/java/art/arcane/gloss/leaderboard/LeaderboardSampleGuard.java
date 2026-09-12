package art.arcane.gloss.leaderboard;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protection against a placeholder expansion that blocks on I/O. Three samples past the budget put
 * a leaderboard's source aside for five minutes; the sweep keeps running for every other board.
 */
public final class LeaderboardSampleGuard {
    static final long SLOW_SAMPLE_NANOS = 5_000_000L;
    static final int SLOW_SAMPLES = 3;
    static final long QUARANTINE_MS = 300_000L;

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public boolean available(String id, long nowMs) {
        State state = states.get(id);
        return state == null || nowMs >= state.until;
    }

    /** @return true when this sample is the one that started a quarantine, so it is logged once */
    public boolean record(String id, long elapsedNanos, long nowMs) {
        if (elapsedNanos < SLOW_SAMPLE_NANOS) {
            states.remove(id);
            return false;
        }
        State current = states.get(id);
        if (current != null && current.until > 0L && nowMs < current.until) {
            return false;
        }
        boolean expired = current != null && current.until > 0L;
        int slow = current == null || expired ? 1 : current.slow + 1;
        if (slow < SLOW_SAMPLES) {
            states.put(id, new State(slow, 0L));
            return false;
        }
        states.put(id, new State(0, nowMs + QUARANTINE_MS));
        return true;
    }

    public void clear() {
        states.clear();
    }

    private record State(int slow, long until) {
    }
}
