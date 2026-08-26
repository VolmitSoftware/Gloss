package art.arcane.gloss.doc;

import java.util.Objects;
import java.util.function.LongSupplier;

final class HotloadBatchGate {
    private final long cooldownNanos;
    private final LongSupplier clock;

    private boolean inFlight;
    private boolean queued;
    private long deferredUntilNanos;

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
        if (clock.getAsLong() < deferredUntilNanos) {
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
    }

    synchronized void deferFromNow() {
        deferredUntilNanos = saturatingAdd(clock.getAsLong(), cooldownNanos);
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
        deferredUntilNanos = 0L;
    }

    private static long saturatingAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }
}
