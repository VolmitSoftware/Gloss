package art.arcane.gloss.bubble;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BubbleStackMathTest {
    @Test
    void stackOffsetLiftsBySpreadTimesLinesAbove() {
        for (int liveCount = 1; liveCount < 6; liveCount++) {
            for (int lineIndex = 0; lineIndex < liveCount; lineIndex++) {
                assertEquals(
                    0.26D * (liveCount - lineIndex),
                    BubbleStackMath.stackOffset(0.26D, lineIndex, liveCount),
                    1.0E-12D
                );
            }
        }
    }

    @Test
    void stackOffsetClampsToZeroOutsideLiveRange() {
        assertEquals(0.0D, BubbleStackMath.stackOffset(0.26D, 0, 0), 0.0D);
        assertEquals(0.0D, BubbleStackMath.stackOffset(0.26D, 3, 1), 0.0D);
    }

    @Test
    void stackOffsetProducesKnownLegacyValues() {
        assertEquals(0.26D, BubbleStackMath.stackOffset(0.26D, 0, 1), 1.0E-12D);
        assertEquals(0.78D, BubbleStackMath.stackOffset(0.26D, 0, 3), 1.0E-12D);
        assertEquals(0.52D, BubbleStackMath.stackOffset(0.26D, 1, 3), 1.0E-12D);
        assertEquals(0.26D, BubbleStackMath.stackOffset(0.26D, 2, 3), 1.0E-12D);
        assertEquals(0.0D, BubbleStackMath.stackOffset(0.26D, 0, 0), 1.0E-12D);
    }

    @Test
    void flyAwayRampMatchesLegacyEasing() {
        assertEquals(0.0D, BubbleStackMath.flyAway(2500L), 0.0D);
        assertEquals(0.0D, BubbleStackMath.flyAway(2000L), 0.0D);
        assertEquals(Math.pow(0.5D, 16.0D) * 10.0D, BubbleStackMath.flyAway(1000L), 1.0E-12D);
        assertEquals(10.0D, BubbleStackMath.flyAway(0L), 1.0E-12D);
        assertEquals(10.0D, BubbleStackMath.flyAway(-100L), 1.0E-12D);
    }

    @Test
    void offsetYCombinesBaseStackAndFlyAway() {
        double expected = 0.86D + BubbleStackMath.stackOffset(0.26D, 0, 2) + BubbleStackMath.flyAway(500L);
        assertEquals(expected, BubbleStackMath.offsetY(0.26D, 0, 2, 500L, true), 1.0E-12D);
    }

    @Test
    void offsetYSkipsFlyAwayWhenDisabled() {
        double expected = 0.86D + BubbleStackMath.stackOffset(0.26D, 0, 2);
        assertEquals(expected, BubbleStackMath.offsetY(0.26D, 0, 2, 500L, false), 1.0E-12D);
        assertEquals(expected, BubbleStackMath.offsetY(0.26D, 0, 2, 0L, false), 1.0E-12D);
    }
}
