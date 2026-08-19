package art.arcane.gloss.indicator;

import art.arcane.volmlib.util.scheduling.SlidingWindowRateLimiter;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndicatorBudgetTest {
    private static final long WINDOW_MS = 1000L;

    @Test
    void freshProbeIsNeverSaturated() {
        IndicatorBudget budget = new IndicatorBudget(WINDOW_MS);
        assertFalse(budget.saturated(0L, 1));
        assertFalse(budget.saturated(0L, 40));
    }

    @Test
    void probeSaturatesOnlyAfterTheWholeCapIsRecordedInsideOneWindow() {
        IndicatorBudget budget = new IndicatorBudget(WINDOW_MS);
        for (int index = 0; index < 3; index++) {
            assertFalse(budget.saturated(index, 4));
            budget.record(index, 4);
        }
        assertFalse(budget.saturated(3L, 4));
        budget.record(3L, 4);
        assertTrue(budget.saturated(4L, 4));
    }

    @Test
    void probeClearsExactlyWhenTheOldestRecordLeavesTheWindow() {
        IndicatorBudget budget = new IndicatorBudget(WINDOW_MS);
        budget.record(0L, 2);
        budget.record(100L, 2);

        assertTrue(budget.saturated(999L, 2));
        assertFalse(budget.saturated(1000L, 2));
    }

    @Test
    void probeAgreesWithTheAuthoritativeLimiterAtEveryStep() {
        int limit = 5;
        AtomicLong clock = new AtomicLong();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(WINDOW_MS, clock::get);
        IndicatorBudget budget = new IndicatorBudget(WINDOW_MS);
        long[] stamps = {0L, 10L, 20L, 30L, 40L, 50L, 999L, 1000L, 1001L, 1500L, 1999L, 2000L, 2001L, 2500L, 3000L};

        for (long now : stamps) {
            clock.set(now);
            boolean saturated = budget.saturated(now, limit);
            boolean acquired = limiter.tryAcquire(limit);
            assertEquals(!acquired, saturated, "pre-gate disagreed with the limiter at " + now);
            if (acquired) {
                budget.record(now, limit);
            }
        }
    }

    @Test
    void probeNeverBlocksWhenTheLimiterStillHasRoom() {
        int limit = 3;
        AtomicLong clock = new AtomicLong();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(WINDOW_MS, clock::get);
        IndicatorBudget budget = new IndicatorBudget(WINDOW_MS);

        for (long now = 0L; now <= 5000L; now += 37L) {
            clock.set(now);
            boolean saturated = budget.saturated(now, limit);
            boolean acquired = limiter.tryAcquire(limit);
            if (acquired) {
                assertFalse(saturated, "pre-gate shed an event the cap would have allowed at " + now);
                budget.record(now, limit);
            }
        }
    }

    @Test
    void aCapChangeResetsTheProbeInsteadOfBlockingOnStaleState() {
        IndicatorBudget budget = new IndicatorBudget(WINDOW_MS);
        budget.record(0L, 2);
        budget.record(1L, 2);
        assertTrue(budget.saturated(2L, 2));

        assertFalse(budget.saturated(2L, 8));
        budget.record(2L, 8);
        assertFalse(budget.saturated(3L, 2));
    }

    @Test
    void aZeroOrNegativeCapIsTreatedAsOne() {
        IndicatorBudget budget = new IndicatorBudget(WINDOW_MS);
        budget.record(0L, 0);
        assertTrue(budget.saturated(999L, 0));
        assertFalse(budget.saturated(1000L, -4));
    }
}
