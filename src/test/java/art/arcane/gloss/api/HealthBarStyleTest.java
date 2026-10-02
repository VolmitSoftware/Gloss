package art.arcane.gloss.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HealthBarStyleTest {
    @Test
    void authoredGlyphsThresholdsAndRecentDamageRemainDistinct() {
        HealthBarStyle style = new HealthBarStyle("#", ".", "H", "W", "C", "D", "E",
            0.8D, 0.4D, 2);
        assertEquals("W#####D##E...", style.render(10, 5, 10, 7));
        assertEquals("C##DE........", style.render(10, 2, 10, 2));
        assertEquals("H#########DE.", style.render(10, 9, 10, 9));
        assertEquals("1.24", style.number(1.235D));
    }

    @Test
    void thresholdsAndPrecisionNormalizeAtLoad() {
        HealthBarStyle style = new HealthBarStyle(null, null, null, null, null, null, null,
            0.2D, 0.8D, 100);
        assertEquals(0.2D, style.criticalThreshold());
        assertEquals(6, style.decimals());
        assertEquals("0", style.number(Double.NaN));
    }
}
