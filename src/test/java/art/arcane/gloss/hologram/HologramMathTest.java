package art.arcane.gloss.hologram;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramMathTest {
    @Test
    void viewRangeMultiplierScalesAgainstPaperBase() {
        assertEquals(0.75F, HologramMath.viewRangeMultiplier(48.0D), 1.0E-6F);
        assertEquals(1.0F, HologramMath.viewRangeMultiplier(64.0D), 1.0E-6F);
        assertEquals(2.0F, HologramMath.viewRangeMultiplier(128.0D), 1.0E-6F);
        assertEquals(0.0625F, HologramMath.viewRangeMultiplier(4.0D), 1.0E-6F);
    }

    @Test
    void requiresViewerRenderingDetectsPlaceholderLines() {
        assertTrue(HologramMath.requiresViewerRendering(List.of("&7Hello", "%player_name%")));
        assertTrue(HologramMath.requiresViewerRendering(List.of("100% pure")));
    }

    @Test
    void requiresViewerRenderingIgnoresStaticLines() {
        assertFalse(HologramMath.requiresViewerRendering(List.of()));
        assertFalse(HologramMath.requiresViewerRendering(List.of("&dWelcome", "&7No tokens here")));
    }
}
