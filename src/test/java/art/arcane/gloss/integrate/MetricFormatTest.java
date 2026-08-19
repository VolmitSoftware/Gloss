package art.arcane.gloss.integrate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetricFormatTest {
    @Test
    void unavailableValuesRenderAsTheEmptyString() {
        assertEquals("", MetricFormat.compact(null));
        assertEquals("", MetricFormat.compact(Double.NaN));
        assertEquals("", MetricFormat.compact(Double.POSITIVE_INFINITY));
    }

    @Test
    void smallValuesKeepAtMostTwoDecimalsWithoutTrailingZeroes() {
        assertEquals("0", MetricFormat.compact(0.0D));
        assertEquals("42", MetricFormat.compact(42.0D));
        assertEquals("3.14", MetricFormat.compact(3.14159D));
        assertEquals("128.5", MetricFormat.compact(128.5D));
        assertEquals("-7.25", MetricFormat.compact(-7.25D));
        assertEquals("999", MetricFormat.compact(999.0D));
    }

    @Test
    void largeValuesCollapseOntoUnitSuffixes() {
        assertEquals("1K", MetricFormat.compact(1000.0D));
        assertEquals("1.2K", MetricFormat.compact(1234.0D));
        assertEquals("1.5M", MetricFormat.compact(1500000.0D));
        assertEquals("2B", MetricFormat.compact(2.0E9D));
        assertEquals("3.5T", MetricFormat.compact(3.5E12D));
        assertEquals("-1.2K", MetricFormat.compact(-1234.0D));
    }
}
