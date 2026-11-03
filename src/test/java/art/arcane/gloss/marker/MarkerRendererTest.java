package art.arcane.gloss.marker;

import art.arcane.gloss.text.TextPipeline;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkerRendererTest {
    @Test
    void edgeArrowTextUsesTheTextPipeline() {
        assertEquals("§f>", MarkerRenderer.edgeText(new TextPipeline(null), null, "&f>"));
    }

    @Test
    void edgeArrowRotatesIntoEachComputedBearing() {
        assertEquals(0D, MarkerRenderer.edgePresentation(EdgeIndicatorMath.Bearing.RIGHT).rotationZDegrees());
        assertEquals(90D, MarkerRenderer.edgePresentation(EdgeIndicatorMath.Bearing.UP).rotationZDegrees());
        assertEquals(180D, MarkerRenderer.edgePresentation(EdgeIndicatorMath.Bearing.LEFT).rotationZDegrees());
        assertEquals(270D, MarkerRenderer.edgePresentation(EdgeIndicatorMath.Bearing.DOWN).rotationZDegrees());
    }
}
