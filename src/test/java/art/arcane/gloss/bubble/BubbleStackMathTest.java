package art.arcane.gloss.bubble;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BubbleStackMathTest {
    @Test
    void stackOffsetUsesEveryWrappedLineInAndBelowTheBlock() {
        assertEquals(0.26D, BubbleStackMath.stackOffset(0.26D, 1), 1.0E-12D);
        assertEquals(0.78D, BubbleStackMath.stackOffset(0.26D, 3), 1.0E-12D);
        assertEquals(1.30D, BubbleStackMath.stackOffset(0.26D, 5), 1.0E-12D);
    }

    @Test
    void nonPositiveStackInputsDoNotMoveBelowTheBase() {
        assertEquals(0.0D, BubbleStackMath.stackOffset(0.26D, 0), 0.0D);
        assertEquals(0.0D, BubbleStackMath.stackOffset(0.26D, -3), 0.0D);
        assertEquals(0.0D, BubbleStackMath.stackOffset(-1.0D, 4), 0.0D);
    }

    @Test
    void offsetYCombinesBaseLiftAndMultilineStackHeight() {
        assertEquals(0.86D + 1.04D, BubbleStackMath.offsetY(0.26D, 4), 1.0E-12D);
        assertEquals(0.86D, BubbleStackMath.offsetY(0.26D, 0), 1.0E-12D);
    }
}
