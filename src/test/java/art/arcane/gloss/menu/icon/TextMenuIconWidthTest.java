package art.arcane.gloss.menu.icon;

import art.arcane.gloss.api.HologramBox;
import art.arcane.gloss.text.TextDisplayLayout;
import art.arcane.gloss.util.common.TextUtils;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextMenuIconWidthTest {
    @Test
    void narrowGlyphsAndSpacesMeasureNarrowerThanWideGlyphs() {
        int thin = pixels("iiiiiiiiii");
        int spaced = pixels("a a a a a ");
        int wide = pixels("WWWWWWWWWW");

        assertTrue(thin < spaced);
        assertTrue(spaced < wide);
    }

    @Test
    void aLineMeasuresEachGlyphAdvancePlusTheBackgroundPixel() {
        assertEquals(6 * 4 + 1, pixels("WWWW"));
    }

    @Test
    void colorCodesAddNothingAndBoldAddsAPixelPerGlyph() {
        assertEquals(pixels("WWWW"), pixels("&cW&aW&bW&dW"));
        assertEquals(pixels("WWWW") + 4, pixels("&lWWWW"));
    }

    @Test
    void theWidestLineSetsTheWidth() {
        List<Component> lines = List.of(TextUtils.parse("WW"), TextUtils.parse("WWWW"), TextUtils.parse("W"));

        assertEquals(pixels("WWWW"), TextMenuIcon.measurePixels(lines, TextDisplayLayout.FULL_WIDTH, HologramBox.defaults()));
    }

    @Test
    void theDisplayLineWidthCapsALongLine() {
        List<Component> lines = List.of(TextUtils.parse("WWWW WWWW WWWW"));

        assertEquals(6 * 4 + 1, TextMenuIcon.measurePixels(lines, 30, HologramBox.defaults()));
    }

    private static int pixels(String text) {
        return TextMenuIcon.measurePixels(List.of(TextUtils.parse(text)), TextDisplayLayout.FULL_WIDTH, HologramBox.defaults());
    }
}
