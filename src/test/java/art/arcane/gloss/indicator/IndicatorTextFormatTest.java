package art.arcane.gloss.indicator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class IndicatorTextFormatTest {
    @Test
    void zeroDecimalsRendersIntegerText() {
        assertEquals("3", IndicatorTextFormat.format(3.2D, 0));
        assertEquals("4", IndicatorTextFormat.format(3.7D, 0));
        assertEquals("5", IndicatorTextFormat.format(5.0D, 0));
    }

    @Test
    void subUnitDamageKeepsValueWithDecimals() {
        assertEquals("0.5", IndicatorTextFormat.format(0.5D, 1));
        assertEquals("0.25", IndicatorTextFormat.format(0.25D, 2));
        assertNotEquals("0", IndicatorTextFormat.format(0.5D, 1));
    }

    @Test
    void decimalsRenderFixedWidth() {
        assertEquals("1.0", IndicatorTextFormat.format(1.0D, 1));
        assertEquals("2.50", IndicatorTextFormat.format(2.5D, 2));
    }
}
