package art.arcane.gloss.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FontMetricsTest {
    private final FontMetrics metrics = FontMetrics.load();

    @Test
    void widthSumsTheVanillaAdvanceTable() {
        assertEquals(24, metrics.width("Hello"));
        assertEquals(6, metrics.width("H"));
        assertEquals(2, metrics.width("i"));
        assertEquals(3, metrics.width("l"));
        assertEquals(4, metrics.width(" "));
        assertEquals(9, metrics.width("█"));
        assertEquals(0, metrics.width(""));
    }

    @Test
    void unknownGlyphsUseTheDefaultWidth() {
        assertEquals(6, metrics.defaultWidth());
        assertEquals(6, metrics.width("é"));
        assertEquals(6, metrics.width(0x1F600));
    }

    @Test
    void colorCodesAreZeroWidthAndBoldAddsOnePixelPerGlyph() {
        assertEquals(24, metrics.width("&cHello"));
        assertEquals(24, metrics.width("§cHello"));
        assertEquals(29, metrics.width("&lHello"));
        assertEquals(18, metrics.width("§lHi§rHi"));
        assertEquals(18, metrics.width("&lHi&fHi"));
        assertEquals(24, metrics.width("&#FF00FFHello"));
        assertEquals(24, metrics.width("&x&F&F&0&0&F&FHello"));
    }

    @Test
    void miniMessageTagsAreZeroWidthAndBoldTagsCount() {
        assertEquals(24, metrics.width("<red>Hello</red>"));
        assertEquals(29, metrics.width("<bold>Hello</bold>"));
        assertEquals(18, metrics.width("<b>Hi</b>Hi"));
        assertEquals(6, metrics.width("<font:gloss:glyphs></font>"));
    }

    @Test
    void declaredCodepointsOverrideTheTable() {
        metrics.declare(0xE000, 32);
        assertEquals(32, metrics.width(0xE000));
        assertEquals(32, metrics.width(""));
        assertEquals(38, metrics.width("" + "H"));
        metrics.replaceDeclared(java.util.Map.of(0xE001, 12));
        assertEquals(6, metrics.width(0xE000));
        assertEquals(12, metrics.width(0xE001));
    }

    @Test
    void tableCoversEveryPrintableAsciiCharacter() {
        for (int codepoint = 0x20; codepoint <= 0x7E; codepoint++) {
            assertEquals(true, metrics.declared(codepoint), "missing ascii width for " + (char) codepoint);
        }
        assertEquals("26.2", metrics.version());
    }
}
