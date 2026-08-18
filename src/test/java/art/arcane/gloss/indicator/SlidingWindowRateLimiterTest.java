package art.arcane.gloss.indicator;

import art.arcane.volmlib.util.scheduling.SlidingWindowRateLimiter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlidingWindowRateLimiterTest {
    @Test
    void capsAcquisitionsWithinOneSecondWindow() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
        assertTrue(limiter.tryAcquire(0L, 3));
        assertTrue(limiter.tryAcquire(100L, 3));
        assertTrue(limiter.tryAcquire(200L, 3));
        assertFalse(limiter.tryAcquire(300L, 3));
        assertFalse(limiter.tryAcquire(999L, 3));
    }

    @Test
    void windowSlidesInsteadOfResetting() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
        assertTrue(limiter.tryAcquire(0L, 2));
        assertTrue(limiter.tryAcquire(900L, 2));
        assertFalse(limiter.tryAcquire(950L, 2));
        assertTrue(limiter.tryAcquire(1000L, 2));
        assertFalse(limiter.tryAcquire(1100L, 2));
        assertTrue(limiter.tryAcquire(1900L, 2));
    }

    @Test
    void limitChangeAppliesImmediately() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
        assertTrue(limiter.tryAcquire(0L, 1));
        assertFalse(limiter.tryAcquire(10L, 1));
        assertTrue(limiter.tryAcquire(20L, 2));
    }

    @Test
    void expiryIsExactlyOneSecond() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
        assertTrue(limiter.tryAcquire(500L, 1));
        assertFalse(limiter.tryAcquire(1499L, 1));
        assertTrue(limiter.tryAcquire(1500L, 1));
    }
}
