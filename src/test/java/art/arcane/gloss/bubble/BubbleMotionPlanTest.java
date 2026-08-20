package art.arcane.gloss.bubble;

import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.bubble.BubbleMotionPlan.BubbleMotionContext;
import art.arcane.gloss.bubble.BubbleMotionPlan.BubbleMotionSample;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BubbleMotionPlanTest {
    private static final double EPSILON = 1.0E-12D;

    @Test
    void defaultMotionExactlyReproducesTheOldFlyAwayCurve() {
        BubbleMotionPlan plan = BubbleMotionPlan.compile(BubbleStyleDoc.DEFAULTS.motion());

        assertEquals(0.0D, sample(plan, 2500L).translationY(), 0.0D);
        assertEquals(0.0D, sample(plan, 2000L).translationY(), 0.0D);
        assertEquals(Math.pow(0.5D, 16.0D) * 10.0D, sample(plan, 1000L).translationY(), EPSILON);
        assertEquals(10.0D, sample(plan, 0L).translationY(), EPSILON);
    }

    @Test
    void expressionsCanFadeShrinkRotateAndFollowAnArc() {
        BubbleStyleDoc.Motion motion = new BubbleStyleDoc.Motion(
            new BubbleStyleDoc.Axis("3 * t", "12 * t * (1 - t) - 2 * t", "0"),
            new BubbleStyleDoc.Axis("1 - smoothstep(0.5, 1, t)", "1 - smoothstep(0.5, 1, t)", "1"),
            new BubbleStyleDoc.Axis("0", "0", "450 * t"),
            "1 - smoothstep(0.7, 1, t)");
        BubbleMotionSample halfway = BubbleMotionPlan.compile(motion).sample(
            new BubbleMotionContext(0.5D, 2500.0D, 5000.0D, 1, 3, 2, 1.5D, 0.25D));

        assertEquals(1.5D, halfway.translationX(), EPSILON);
        assertEquals(2.0D, halfway.translationY(), EPSILON);
        assertEquals(1.0D, halfway.presentation().scaleX(), EPSILON);
        assertEquals(225.0D, halfway.presentation().rotationZDegrees(), EPSILON);
        assertEquals(1.0D, halfway.presentation().opacity(), EPSILON);
    }

    @Test
    void motionVariablesExposeStackAndLifetimeState() {
        BubbleStyleDoc.Motion motion = new BubbleStyleDoc.Motion(
            new BubbleStyleDoc.Axis("stackIndex + stackCount", "lineCount + stackY", "seed + pi"),
            null, null, "remaining");
        BubbleMotionSample sample = BubbleMotionPlan.compile(motion).sample(
            new BubbleMotionContext(0.25D, 1250.0D, 5000.0D, 2, 5, 3, 1.5D, 0.125D));

        assertEquals(7.0D, sample.translationX(), EPSILON);
        assertEquals(4.5D, sample.translationY(), EPSILON);
        assertEquals(Math.PI + 0.125D, sample.translationZ(), EPSILON);
        assertEquals(0.75D, sample.presentation().opacity(), EPSILON);
    }

    @Test
    void outputsUseTheRuntimeSafetyBounds() {
        BubbleStyleDoc.Motion motion = new BubbleStyleDoc.Motion(
            new BubbleStyleDoc.Axis("100", "-100", "0"),
            new BubbleStyleDoc.Axis("20", "-2", "1"),
            new BubbleStyleDoc.Axis("-90", "450", "720"), "2");
        BubbleMotionSample sample = BubbleMotionPlan.compile(motion).sample(
            new BubbleMotionContext(0.0D, 0.0D, 5000.0D, 0, 1, 1, 1.0D, 0.0D));
        HologramPresentation presentation = sample.presentation();

        assertEquals(64.0D, sample.translationX(), EPSILON);
        assertEquals(-64.0D, sample.translationY(), EPSILON);
        assertEquals(16.0D, presentation.scaleX(), EPSILON);
        assertEquals(0.0D, presentation.scaleY(), EPSILON);
        assertEquals(270.0D, presentation.rotationXDegrees(), EPSILON);
        assertEquals(90.0D, presentation.rotationYDegrees(), EPSILON);
        assertEquals(0.0D, presentation.rotationZDegrees(), EPSILON);
        assertEquals(1.0D, presentation.opacity(), EPSILON);
    }

    @Test
    void aRuntimeOnlyExpressionFailureFallsBackPerChannel() {
        BubbleStyleDoc.Motion motion = new BubbleStyleDoc.Motion(
            new BubbleStyleDoc.Axis("t == 0.33 ? 1 / 0 : 3", "2", "1"), null, null, "1");
        BubbleMotionSample sample = BubbleMotionPlan.compile(motion).sample(
            new BubbleMotionContext(0.33D, 1650.0D, 5000.0D, 0, 1, 1, 1.0D, 0.0D));

        assertEquals(0.0D, sample.translationX(), EPSILON);
        assertEquals(2.0D, sample.translationY(), EPSILON);
    }

    private static BubbleMotionSample sample(BubbleMotionPlan plan, long remainingMs) {
        double ageMs = 5000.0D - remainingMs;
        return plan.sample(new BubbleMotionContext(ageMs / 5000.0D, ageMs, 5000.0D,
            0, 1, 1, 1.12D, 0.5D));
    }
}
