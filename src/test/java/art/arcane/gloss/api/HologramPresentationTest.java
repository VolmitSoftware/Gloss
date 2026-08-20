package art.arcane.gloss.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HologramPresentationTest {
    @Test
    void normalizesEveryPublicPresentationChannel() {
        HologramPresentation presentation = new HologramPresentation(
            -1.0D, 20.0D, Double.NaN, -90.0D, 450.0D, Double.POSITIVE_INFINITY, -1.0D);

        assertEquals(0.0D, presentation.scaleX());
        assertEquals(16.0D, presentation.scaleY());
        assertEquals(1.0D, presentation.scaleZ());
        assertEquals(270.0D, presentation.rotationXDegrees());
        assertEquals(90.0D, presentation.rotationYDegrees());
        assertEquals(0.0D, presentation.rotationZDegrees());
        assertEquals(0.0D, presentation.opacity());
    }

    @Test
    void identityUsesNeutralPresentationValues() {
        assertEquals(new HologramPresentation(1.0D, 1.0D, 1.0D, 0.0D, 0.0D, 0.0D, 1.0D),
            HologramPresentation.identity());
    }
}
