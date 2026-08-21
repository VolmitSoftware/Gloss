package art.arcane.gloss.drop;

import art.arcane.gloss.GlossConfig;
import org.junit.jupiter.api.Test;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealDropAnimationEngineTest {
    private static final UUID ITEM_ID = UUID.fromString("605486f7-d737-4300-9207-cf957cd5b71d");

    private static GlossConfig.RealDrops defaults() {
        return RealDropSettingsDoc.DEFAULTS.toConfig(true);
    }

    private static RealDropAnimationInput input(boolean supported, boolean submerged, boolean bounced,
                                                double deltaX, double deltaZ, double velocityX,
                                                double velocityY, double velocityZ, double impactSpeed) {
        return new RealDropAnimationInput(
            2, supported, submerged, false, bounced, deltaX, deltaZ,
            velocityX, velocityY, velocityZ, impactSpeed);
    }

    @Test
    void bounceRemainsAirborneInTheImpactSample() {
        GlossConfig.RealDrops config = defaults();
        RealDropAnimationState state = new RealDropAnimationState(
            new Quaternionf().rotateXYZ(0.4F, 0.2F, -0.3F),
            RealDropModel.spin(ITEM_ID, 0, config.motion()), false);

        RealDropAnimationFrame frame = RealDropAnimationEngine.advance(
            ITEM_ID, RealDropModel.ModelKind.BLOCK, state,
            input(false, false, true, 0.0D, 0.0D, 0.1D, 0.24D, 0.0D, 0.42D), config);

        assertEquals(RealDropAnimationState.Phase.REBOUNDING, frame.phase());
        assertFalse(frame.settled());
        assertEquals(0.42D, frame.impactSpeed());
    }

    @Test
    void contactAdvancesTheExistingQuaternionInsteadOfReplacingIt() {
        GlossConfig.RealDrops config = defaults();
        Quaternionf initial = new Quaternionf().rotateXYZ(0.73F, -0.31F, 0.42F);
        RealDropAnimationState state = new RealDropAnimationState(
            initial, RealDropModel.spin(ITEM_ID, 0, config.motion()), false);

        RealDropAnimationFrame frame = RealDropAnimationEngine.advance(
            ITEM_ID, RealDropModel.ModelKind.BLOCK, state,
            input(true, false, false, 0.08D, 0.03D, 0.08D, 0.0D, 0.03D, 0.37D), config);

        assertEquals(RealDropAnimationState.Phase.ROLLING, frame.phase());
        assertTrue(Math.abs(initial.dot(frame.rotation())) > 0.75F);
        assertNotEquals(RealDropModel.landingRotation(
            ITEM_ID, RealDropModel.ModelKind.BLOCK, config.landing()), frame.rotation());
    }

    @Test
    void flatItemContinuouslySettlesOntoItsBroadFace() {
        GlossConfig.RealDrops config = defaults();
        RealDropAnimationState state = new RealDropAnimationState(
            new Quaternionf().rotateXYZ(0.15F, 0.8F, 0.55F),
            RealDropModel.spin(ITEM_ID, 0, config.motion()), false);

        RealDropAnimationFrame first = RealDropAnimationEngine.advance(
            ITEM_ID, RealDropModel.ModelKind.FLAT, state,
            input(true, false, false, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.2D), config);
        assertEquals(RealDropAnimationState.Phase.SETTLING, first.phase());
        assertFalse(first.settled());

        RealDropAnimationFrame frame = first;
        for (int sample = 0; sample < 32 && !frame.settled(); sample++) {
            frame = RealDropAnimationEngine.advance(
                ITEM_ID, RealDropModel.ModelKind.FLAT, state,
                input(true, false, false, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D), config);
        }

        assertTrue(frame.settled());
        assertEquals(RealDropAnimationState.Phase.SETTLED, frame.phase());
        assertTrue(Math.abs(frame.rotation().transform(
            new Vector3f(0.0F, 0.0F, 1.0F)).y()) > 0.99999F);
    }

    @Test
    void settledFramesStopEmittingPoseChanges() {
        GlossConfig.RealDrops config = defaults();
        Quaternionf settledRotation = RealDropModel.landingRotation(
            ITEM_ID, RealDropModel.ModelKind.FLAT, config.landing());
        RealDropAnimationState state = new RealDropAnimationState(
            settledRotation, RealDropModel.spin(ITEM_ID, 0, config.motion()), true);

        RealDropAnimationFrame frame = null;
        for (int sample = 0; sample < 4; sample++) {
            frame = RealDropAnimationEngine.advance(
                ITEM_ID, RealDropModel.ModelKind.FLAT, state,
                input(true, false, false, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D), config);
        }
        assertTrue(frame.settled());

        RealDropAnimationFrame unchanged = RealDropAnimationEngine.advance(
            ITEM_ID, RealDropModel.ModelKind.FLAT, state,
            input(true, false, false, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D), config);
        assertFalse(unchanged.poseChanged());
        assertEquals(config.limits().settledPollIntervalTicks(), unchanged.pollDelayTicks());
    }

    @Test
    void submersionOwnsThePhaseAndNeverReportsSettled() {
        GlossConfig.RealDrops config = defaults();
        RealDropAnimationState state = new RealDropAnimationState(
            new Quaternionf(), RealDropModel.spin(ITEM_ID, 0, config.motion()), false);

        RealDropAnimationFrame frame = RealDropAnimationEngine.advance(
            ITEM_ID, RealDropModel.ModelKind.THIN, state,
            input(false, true, false, 0.0D, 0.0D, 0.03D, 0.02D, 0.01D, 0.0D), config);

        assertEquals(RealDropAnimationState.Phase.SUBMERGED, frame.phase());
        assertFalse(frame.settled());
    }
}
