package art.arcane.gloss.bubble;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 1 characterization pins for the chat-bubble lifecycle math: spawn stacking,
 * fly-away expiry easing, and the message-to-lines split that drives per-line stagger.
 * These pin OUTCOMES of the pure surfaces the service composes; they are frozen
 * contracts for the Lane B refactors (stored line index, sender-state pruning,
 * timestamp-swept expiry). Assertions may only change with an explicit report note.
 */
class CharacterizationBubbleLifecycleTest {
    private static final double EPSILON = 1.0E-9D;
    private static final double BASE_LIFT = 0.86D;
    private static final long FLY_AWAY_WINDOW_MS = 2000L;
    private static final double FLY_AWAY_HEIGHT = 10.0D;
    private static final long FAR_FROM_EXPIRY_MS = 60_000L;

    // --- spawn stacking: line index and live count place each bubble line ---

    @Test
    void stackPlacesOldestLineHighestAndEachYoungerLineOneSpreadLower() {
        double spread = 0.25D;
        int liveCount = 4;
        double[] lifts = new double[liveCount];
        for (int index = 0; index < liveCount; index++) {
            lifts[index] = BubbleStackMath.offsetY(spread, index, liveCount, FAR_FROM_EXPIRY_MS, false);
        }
        for (int index = 0; index < liveCount; index++) {
            assertEquals(BASE_LIFT + spread * (liveCount - index), lifts[index], EPSILON);
        }
        for (int index = 1; index < liveCount; index++) {
            assertEquals(spread, lifts[index - 1] - lifts[index], EPSILON);
        }
    }

    @Test
    void indexZeroIsTheTopOfTheStack() {
        // The service clamps a removed record's indexOf(-1) to 0, so index 0 doubles as
        // the "record no longer tracked" fallback: it must resolve to the stack top.
        double spread = 0.25D;
        int liveCount = 3;
        double topLift = BubbleStackMath.offsetY(spread, 0, liveCount, FAR_FROM_EXPIRY_MS, false);
        assertEquals(BASE_LIFT + spread * liveCount, topLift, EPSILON);
        for (int index = 1; index < liveCount; index++) {
            assertTrue(topLift > BubbleStackMath.offsetY(spread, index, liveCount, FAR_FROM_EXPIRY_MS, false));
        }
    }

    @Test
    void indexEqualToLiveCountRestsAtTheBaseLift() {
        assertEquals(BASE_LIFT, BubbleStackMath.offsetY(0.25D, 3, 3, FAR_FROM_EXPIRY_MS, false), EPSILON);
    }

    @Test
    void zeroSpreadCollapsesTheStackToTheBaseLift() {
        for (int index = 0; index < 4; index++) {
            assertEquals(BASE_LIFT, BubbleStackMath.offsetY(0.0D, index, 4, FAR_FROM_EXPIRY_MS, false), EPSILON);
        }
    }

    // --- fly-away expiry easing ---

    @Test
    void flyAwayContributesNothingUntilTheFinalTwoSecondsOfLife() {
        assertEquals(0.0D, BubbleStackMath.flyAway(FLY_AWAY_WINDOW_MS));
        assertEquals(0.0D, BubbleStackMath.flyAway(FLY_AWAY_WINDOW_MS + 1L));
        assertEquals(0.0D, BubbleStackMath.flyAway(FAR_FROM_EXPIRY_MS));
    }

    @Test
    void flyAwayStartsRisingImmediatelyInsideTheWindow() {
        double lift = BubbleStackMath.flyAway(FLY_AWAY_WINDOW_MS - 1L);
        assertTrue(lift > 0.0D);
        assertTrue(lift < 1.0E-6D);
    }

    @Test
    void flyAwayRisesMonotonicallyAsExpiryApproaches() {
        long[] remainings = {1999L, 1500L, 1000L, 500L, 100L, 10L, 0L};
        double previous = -1.0D;
        for (long remaining : remainings) {
            double lift = BubbleStackMath.flyAway(remaining);
            assertTrue(lift > previous, "lift at remaining=" + remaining + " must exceed lift at the prior sample");
            previous = lift;
        }
    }

    @Test
    void flyAwayPeaksAtExactlyTheFlyAwayHeightAtExpiry() {
        assertEquals(FLY_AWAY_HEIGHT, BubbleStackMath.flyAway(0L));
    }

    @Test
    void flyAwayClampsNegativeRemainingToThePeak() {
        // A hologram that outlives its untrack task reports negative remaining time;
        // the bubble must hold at the peak, not overshoot or wrap.
        assertEquals(FLY_AWAY_HEIGHT, BubbleStackMath.flyAway(-1L));
        assertEquals(FLY_AWAY_HEIGHT, BubbleStackMath.flyAway(Long.MIN_VALUE + 1L));
    }

    @Test
    void offsetYAddsFlyAwayOnlyWhenTheStyleEnablesIt() {
        double spread = 0.25D;
        double withFly = BubbleStackMath.offsetY(spread, 0, 1, 0L, true);
        double withoutFly = BubbleStackMath.offsetY(spread, 0, 1, 0L, false);
        assertEquals(BASE_LIFT + spread + FLY_AWAY_HEIGHT, withFly, EPSILON);
        assertEquals(BASE_LIFT + spread, withoutFly, EPSILON);
    }

    // --- message split: line count and order drive the per-line spawn stagger ---

    @Test
    void splitPreservesEveryWordInOrderAcrossWrappedLines() {
        List<String> lines = BubbleLines.split("one two three four five six", 9);
        assertTrue(lines.size() > 1);
        assertEquals("one two three four five six", String.join(" ", lines));
    }

    @Test
    void splitKeepsSoftWrappedLinesWithinTheWrapWidth() {
        for (String line : BubbleLines.split("alpha beta gamma delta epsilon", 12)) {
            assertTrue(line.length() <= 12, "line '" + line + "' exceeds the wrap width");
        }
    }

    @Test
    void messageAtExactlyTheWrapWidthStaysOneLine() {
        assertEquals(List.of("abcdefgh"), BubbleLines.split("abcdefgh", 8));
    }

    @Test
    void colorCodesDoNotCountTowardTheWrapWidth() {
        // Colors are stripped before wrapping, so a color-heavy short message spawns one bubble line.
        assertEquals(List.of("hi there"), BubbleLines.split("§a§lhi §bthere", 8));
    }

    @Test
    void stripColorsRemovesSectionPairsWhereverTheyAppear() {
        assertEquals("hello", BubbleLines.stripColors("§ahello"));
        assertEquals("hello", BubbleLines.stripColors("he§allo"));
        assertEquals("a", BubbleLines.stripColors("§§a"));
    }

    @Test
    void stripColorsKeepsATrailingLoneSectionCharacter() {
        // Current behavior: a terminal section sign with no code character survives the strip.
        assertEquals("hello§", BubbleLines.stripColors("hello§"));
        assertEquals("§", BubbleLines.stripColors("§"));
    }
}
