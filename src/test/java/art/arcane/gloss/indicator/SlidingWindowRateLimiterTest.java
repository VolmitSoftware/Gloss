package art.arcane.gloss.indicator;

import art.arcane.volmlib.util.scheduling.SlidingWindowRateLimiter;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The damage-indicator cap runs on this limiter. Its window used to be driven by the wall-clock
 * timestamp the caller passed in, so an NTP step backwards would park the cap until real time
 * caught up and a step forwards would flush the window and let a full burst through. The window is
 * now driven by the limiter's own monotonic clock; these pin the cap semantics across the change.
 */
class SlidingWindowRateLimiterTest {
    @Test
    void capsAcquisitionsWithinOneSecondWindow() {
        AtomicLong clock = new AtomicLong();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1000L, clock::get);

        assertTrue(limiter.tryAcquire(3));
        clock.set(100L);
        assertTrue(limiter.tryAcquire(3));
        clock.set(200L);
        assertTrue(limiter.tryAcquire(3));
        clock.set(300L);
        assertFalse(limiter.tryAcquire(3));
        clock.set(999L);
        assertFalse(limiter.tryAcquire(3));
    }

    @Test
    void windowSlidesInsteadOfResetting() {
        AtomicLong clock = new AtomicLong();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1000L, clock::get);

        assertTrue(limiter.tryAcquire(2));
        clock.set(900L);
        assertTrue(limiter.tryAcquire(2));
        clock.set(950L);
        assertFalse(limiter.tryAcquire(2));
        clock.set(1000L);
        assertTrue(limiter.tryAcquire(2));
        clock.set(1100L);
        assertFalse(limiter.tryAcquire(2));
        clock.set(1900L);
        assertTrue(limiter.tryAcquire(2));
    }

    @Test
    void limitChangeAppliesImmediately() {
        AtomicLong clock = new AtomicLong();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1000L, clock::get);

        assertTrue(limiter.tryAcquire(1));
        clock.set(10L);
        assertFalse(limiter.tryAcquire(1));
        clock.set(20L);
        assertTrue(limiter.tryAcquire(2));
    }

    @Test
    void expiryIsExactlyOneSecond() {
        AtomicLong clock = new AtomicLong(500L);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1000L, clock::get);

        assertTrue(limiter.tryAcquire(1));
        clock.set(1499L);
        assertFalse(limiter.tryAcquire(1));
        clock.set(1500L);
        assertTrue(limiter.tryAcquire(1));
    }

    @Test
    void theDefaultLimiterUsesItsOwnMonotonicClock() {
        // What the indicator service constructs: no window argument, no caller timestamp.
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();

        assertTrue(limiter.tryAcquire(2));
        assertTrue(limiter.tryAcquire(2));
        assertFalse(limiter.tryAcquire(2));
    }

    /**
     * The cap is driven only by the limiter's own clock. A caller cannot hand it a timestamp at all
     * any more — the wall-clock overload is gone — so an hour-long NTP step in either direction is
     * invisible to the window.
     */
    @Test
    void theWindowIgnoresWallClockEntirely() {
        AtomicLong clock = new AtomicLong();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1000L, clock::get);

        assertTrue(limiter.tryAcquire(1));
        assertFalse(limiter.tryAcquire(1));
        assertFalse(limiter.tryAcquire(1));

        clock.set(1000L);
        assertTrue(limiter.tryAcquire(1));
    }
}
