package art.arcane.gloss.chat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-sender rate and repeat limits. Only an accepted message updates a sender's history, so a
 * player who keeps spamming does not keep pushing their own window forward.
 */
public final class ChatThrottle {
    private static final long MS_PER_TICK = 50L;

    private final Map<UUID, History> histories = new ConcurrentHashMap<>();

    public enum Verdict {
        OK,
        REPEAT,
        TOO_FAST
    }

    public Verdict check(UUID sender, String normalized, long nowMs, ChannelDoc.Throttle settings) {
        History history = histories.get(sender);
        long minIntervalMs = settings.minIntervalTicks() * MS_PER_TICK;
        if (history != null && minIntervalMs > 0L && nowMs - history.lastMs < minIntervalMs) {
            return Verdict.TOO_FAST;
        }
        long windowMs = settings.repeatWindowTicks() * MS_PER_TICK;
        boolean sameRun = history != null && windowMs > 0L
            && history.text.equals(normalized) && nowMs - history.windowStartMs <= windowMs;
        if (sameRun && history.repeats + 1 > settings.maxRepeats()) {
            return Verdict.REPEAT;
        }
        histories.put(sender, sameRun
            ? new History(normalized, nowMs, history.windowStartMs, history.repeats + 1)
            : new History(normalized, nowMs, nowMs, 1));
        return Verdict.OK;
    }

    public void forget(UUID sender) {
        histories.remove(sender);
    }

    public void clear() {
        histories.clear();
    }

    private record History(String text, long lastMs, long windowStartMs, int repeats) {
    }
}
