package art.arcane.gloss.hologram;

import java.util.ArrayDeque;

public final class AnimatorBudgetWindow {
    private final long windowMillis;
    private final ArrayDeque<BudgetSample> samples = new ArrayDeque<>();
    private long recipients;
    private long timeMs = Long.MIN_VALUE;

    public AnimatorBudgetWindow(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    public long advance(long nowMs) {
        if (timeMs == Long.MIN_VALUE || nowMs > timeMs) {
            timeMs = nowMs;
        }
        return timeMs;
    }

    public void discardExpired(long nowMs) {
        long cutoffMs = nowMs - windowMillis;
        while (!samples.isEmpty() && samples.peekFirst().atMs() <= cutoffMs) {
            recipients -= samples.removeFirst().recipients();
        }
    }

    public void record(long nowMs, int recipients) {
        if (recipients <= 0) {
            return;
        }

        samples.addLast(new BudgetSample(nowMs, recipients));
        this.recipients += recipients;
    }

    public long recipientsInWindow() {
        return recipients;
    }

    private record BudgetSample(long atMs, int recipients) {
    }
}
