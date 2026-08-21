package art.arcane.gloss.doc;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotloadBatchGateTest {
    private static final long COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(3L);

    @Test
    void firstRequestedBatchStartsImmediately() {
        AtomicLong clock = new AtomicLong();
        HotloadBatchGate gate = new HotloadBatchGate(COOLDOWN_NANOS, clock::get);

        assertFalse(gate.tryStart());
        gate.request();

        assertTrue(gate.tryStart());
        assertFalse(gate.tryStart());
    }

    @Test
    void burstDuringBatchBecomesOneTrailingBatchAtCooldownBoundary() {
        AtomicLong clock = new AtomicLong();
        HotloadBatchGate gate = new HotloadBatchGate(COOLDOWN_NANOS, clock::get);

        gate.request();
        assertTrue(gate.tryStart());
        gate.request();
        gate.request();
        gate.request();
        gate.complete();

        clock.set(COOLDOWN_NANOS - 1L);
        assertFalse(gate.tryStart());
        clock.set(COOLDOWN_NANOS);
        assertTrue(gate.tryStart());
        gate.complete();

        assertFalse(gate.tryStart());
    }

    @Test
    void cooldownBeginsAfterTheRunningApplyBatchCompletes() {
        AtomicLong clock = new AtomicLong();
        HotloadBatchGate gate = new HotloadBatchGate(COOLDOWN_NANOS, clock::get);

        gate.request();
        assertTrue(gate.tryStart());
        gate.request();
        clock.set(TimeUnit.SECONDS.toNanos(10L));
        assertFalse(gate.tryStart());
        gate.complete();

        clock.addAndGet(COOLDOWN_NANOS - 1L);
        assertFalse(gate.tryStart());
        clock.incrementAndGet();
        assertTrue(gate.tryStart());
    }

    @Test
    void manualDeferralKeepsOneQueuedLatestStateBatch() {
        AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(20L));
        HotloadBatchGate gate = new HotloadBatchGate(COOLDOWN_NANOS, clock::get);

        gate.request();
        gate.request();
        gate.deferFromNow();

        assertFalse(gate.tryStart());
        clock.addAndGet(COOLDOWN_NANOS);
        assertTrue(gate.tryStart());
        gate.complete();
        assertFalse(gate.tryStart());
    }

    @Test
    void rejectedDispatchRetainsTheRequestedBatch() {
        AtomicLong clock = new AtomicLong();
        HotloadBatchGate gate = new HotloadBatchGate(COOLDOWN_NANOS, clock::get);

        gate.request();
        assertTrue(gate.tryStart());
        gate.retry();

        assertTrue(gate.tryStart());
    }

    @Test
    void cancelDropsQueuedAndRunningState() {
        AtomicLong clock = new AtomicLong();
        HotloadBatchGate gate = new HotloadBatchGate(COOLDOWN_NANOS, clock::get);

        gate.request();
        assertTrue(gate.tryStart());
        gate.request();
        gate.cancel();

        assertFalse(gate.tryStart());
    }

    @Test
    void invalidConstructionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new HotloadBatchGate(0L, System::nanoTime));
        assertThrows(NullPointerException.class, () -> new HotloadBatchGate(COOLDOWN_NANOS, null));
    }
}
