package art.arcane.gloss.drop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealDropCarrierMotionTest {
    @Test
    void settledCarrierIgnoresMicroscopicPositionNoise() {
        assertFalse(RealDropService.carrierPositionChanged(
            true, true,
            12.001D, 64.001D, -3.001D,
            12.0D, 64.0D, -3.0D));
    }

    @Test
    void activeCarrierStillTracksMicroscopicMotion() {
        assertTrue(RealDropService.carrierPositionChanged(
            true, false,
            12.001D, 64.001D, -3.001D,
            12.0D, 64.0D, -3.0D));
    }

    @Test
    void settledCarrierTracksMeaningfulMovement() {
        assertTrue(RealDropService.carrierPositionChanged(
            true, true,
            12.01D, 64.0D, -3.0D,
            12.0D, 64.0D, -3.0D));
    }

    @Test
    void carrierWithoutKnownPositionAlwaysMoves() {
        assertTrue(RealDropService.carrierPositionChanged(
            false, true,
            12.0D, 64.0D, -3.0D,
            12.0D, 64.0D, -3.0D));
    }

    @Test
    void regionizedDisplaysStayDetachedFromTheMovingCarrier() {
        assertFalse(RealDropService.usesPassengerCarrier(true));
        assertTrue(RealDropService.usesPassengerCarrier(false));
    }
}
