package art.arcane.gloss.doc;

import java.util.Objects;
import java.util.function.LongSupplier;

final class HotloadBatchGate {
    private final long cooldownNanos;
    private final LongSupplier clock;

    private boolean completed;
    private boolean inFlight;
    private boolean queued;
    private long lastCompletedNanos;

    HotloadBatchGate(long cooldownNanos, LongSupplier clock) {
        if (cooldownNanos <= 0L) {
            throw new IllegalArgumentException("cooldownNanos must be positive");
        }
        this.cooldownNanos = cooldownNanos;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    synchronized void request() {
        queued = true;
    }

    synchronized boolean tryStart() {
        if (inFlight || !queued) {
            return false;
        }
        long now = clock.getAsLong();
        if (completed && now - lastCompletedNanos < cooldownNanos) {
            return false;
        }
        queued = false;
        inFlight = true;
        return true;
    }

    synchronized void complete() {
        if (!inFlight) {
            return;
        }
        inFlight = false;
        completed = true;
        lastCompletedNanos = clock.getAsLong();
    }

    synchronized void deferFromNow() {
        completed = true;
        lastCompletedNanos = clock.getAsLong();
    }

    synchronized void retry() {
        if (!inFlight) {
            return;
        }
        inFlight = false;
        queued = true;
    }

    synchronized void cancel() {
        inFlight = false;
        queued = false;
    }
}
