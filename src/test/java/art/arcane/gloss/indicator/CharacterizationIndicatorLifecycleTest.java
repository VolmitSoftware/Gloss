package art.arcane.gloss.indicator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Step 1 characterization pins for the damage-indicator lifecycle surfaces reachable
 * without seams: the amount-to-text rendering every spawn goes through, and the
 * service's safe construct/disable lifecycle. The debounce window, the two-hop
 * compare ordering, the rate-limit consumption point, and the spawn motion/expiry
 * are service-private against the concrete plugin and are reported as unpinnable
 * in the Step 1 report (no seams permitted in this pass). Frozen contracts;
 * assertion changes require an explicit report note.
 */
class CharacterizationIndicatorLifecycleTest {

    // --- amount rendering: the exact text a spawned indicator shows ---

    @Test
    void integerModeRoundsHalfUp() {
        assertEquals("1", IndicatorTextFormat.format(0.5D, 0));
        assertEquals("2", IndicatorTextFormat.format(1.5D, 0));
        assertEquals("3", IndicatorTextFormat.format(2.5D, 0));
        assertEquals("2", IndicatorTextFormat.format(2.25D, 0));
    }

    @Test
    void negativeDecimalsBehaveExactlyLikeZeroDecimals() {
        assertEquals("2", IndicatorTextFormat.format(2.4D, -1));
        assertEquals("3", IndicatorTextFormat.format(2.5D, -5));
    }

    @Test
    void decimalModeRendersFixedWidthHalfUp() {
        assertEquals("1.3", IndicatorTextFormat.format(1.25D, 1));
        assertEquals("1.8", IndicatorTextFormat.format(1.75D, 1));
        assertEquals("3.00", IndicatorTextFormat.format(3.0D, 2));
        assertEquals("0.50", IndicatorTextFormat.format(0.5D, 2));
    }

    @Test
    void tinyDamageAboveTheServiceDeltaFloorCanStillRenderAsZeroPointZero() {
        // The service suppresses |delta| <= 0.009 but a 0.01 hit passes the gate and,
        // at one decimal, renders "0.0". Current behavior — pinned, not endorsed.
        assertEquals("0.0", IndicatorTextFormat.format(0.01D, 1));
    }

    @Test
    void integerModeUsesNoGroupingOrLocaleFormatting() {
        assertEquals("12345", IndicatorTextFormat.format(12345.0D, 0));
        assertEquals("12345.6", IndicatorTextFormat.format(12345.6D, 1));
    }

    // --- lifecycle: construct/disable ordering safety ---

    @Test
    void freshServiceReportsZeroActiveIndicators() {
        DamageIndicatorsService service = new DamageIndicatorsService(null);
        assertEquals(0, service.activeCount());
    }

    @Test
    void disableBeforeEnableIsASafeNoOp() {
        // disable() runs unregister + destroyAll + debounce.clear() without touching the
        // plugin; a never-enabled service must survive shutdown ordering unchanged.
        DamageIndicatorsService service = new DamageIndicatorsService(null);
        service.disable();
        service.disable();
        assertEquals(0, service.activeCount());
    }
}
