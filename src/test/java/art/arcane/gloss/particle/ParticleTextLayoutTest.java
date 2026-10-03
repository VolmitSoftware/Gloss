package art.arcane.gloss.particle;

import art.arcane.gloss.api.HologramBox;
import art.arcane.gloss.api.IconTextAlignment;
import art.arcane.gloss.hologram.HologramBoxLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ParticleTextLayoutTest {
    @BeforeEach
    @AfterEach
    void resetCaches() {
        ParticleTextLayout.clearCaches();
    }

    /**
     * Every viewer of the same hologram lays out the same string every tick; the per character
     * pass may only run once for a given (text, scale).
     */
    @Test
    void repeatedTextBoundsShareOneLayout() {
        ParticleRect first = ParticleTextLayout.textBounds("shared caption", 1.0D);
        ParticleRect second = ParticleTextLayout.textBounds("shared caption", 1.0D);

        assertSame(first, second);
    }

    @Test
    void repeatedLineBoundsShareOneResult() {
        List<ParticleRect> first = ParticleTextLayout.lineBounds("one\ntwo", 1.0D);
        List<ParticleRect> second = ParticleTextLayout.lineBounds("one\ntwo", 1.0D);

        assertSame(first, second);
        assertEquals(2, first.size());
    }

    @Test
    void repeatedSpanBoundsShareOneResult() {
        ParticleText.Rendered rendered = ParticleText.render(
            "hit <particles:amount>42</particles>!", value -> value);

        List<ParticleRect> first = ParticleTextLayout.bounds(rendered, "amount", 1.0D, false);
        List<ParticleRect> second = ParticleTextLayout.bounds(rendered, "amount", 1.0D, false);

        assertSame(first, second);
        assertEquals(1, first.size());
    }

    @Test
    void aDifferentScaleIsADifferentLayout() {
        ParticleRect small = ParticleTextLayout.textBounds("caption", 1.0D);
        ParticleRect large = ParticleTextLayout.textBounds("caption", 2.0D);

        assertNotEquals(small, large);
        assertEquals(small.width() * 2.0D, large.width(), 1.0E-9D);
    }

    @Test
    void perLetterAndUnionSpanBoundsStayDistinct() {
        ParticleText.Rendered rendered = ParticleText.render(
            "hit <particles:amount>42</particles>!", value -> value);

        List<ParticleRect> union = ParticleTextLayout.bounds(rendered, "amount", 1.0D, false);
        List<ParticleRect> letters = ParticleTextLayout.bounds(rendered, "amount", 1.0D, true);

        assertEquals(1, union.size());
        assertEquals(2, letters.size());
    }

    @Test
    void twoDifferentTextsKeepTheirOwnBounds() {
        ParticleRect shortText = ParticleTextLayout.textBounds("ab", 1.0D);
        ParticleRect longText = ParticleTextLayout.textBounds("abcdef", 1.0D);

        assertEquals((HologramBoxLayout.measure("ab", 16384, HologramBox.defaults()).textWidth()) * 0.025D,
            shortText.width(), 1.0E-6D);
        assertEquals((HologramBoxLayout.measure("abcdef", 16384, HologramBox.defaults()).textWidth()) * 0.025D,
            longText.width(), 1.0E-6D);
    }

    @Test
    void nativeFontAdvancesAndRowsMatchTheTextBackground() {
        String text = "§lGLOSS\nMeasured text outline";
        HologramBoxLayout nativeLayout = HologramBoxLayout.measure(text, 16384, HologramBox.defaults());
        ParticleRect projection = ParticleTextLayout.textBounds(text, 1D);
        List<ParticleRect> lines = ParticleTextLayout.lineBounds(text, 1D);

        assertEquals((nativeLayout.textWidth()) * 0.025D, projection.width(), 1.0E-6D);
        assertEquals(nativeLayout.textHeight() * 0.025D, projection.height(), 1.0E-6D);
        assertEquals(10D * 0.025D, lines.get(0).centerY() - lines.get(1).centerY(), 1.0E-6D);
        assertEquals(0D, lines.get(1).centerY() + projection.height() / 2D - lines.get(1).height() / 2D,
            1.0E-6D);
    }

    @Test
    void multilineAlignmentUsesTheNativeParagraphWidth() {
        for (IconTextAlignment alignment : IconTextAlignment.values()) {
            ParticleTextLayout.TextStyle style = new ParticleTextLayout.TextStyle(16384, alignment);
            List<ParticleRect> lines = ParticleTextLayout.styledLineBounds("Wide line\nI", 1D, style);
            ParticleRect first = lines.get(0);
            ParticleRect second = lines.get(1);
            switch (alignment) {
                case CENTER -> assertEquals(first.centerX(), second.centerX(), 1.0E-6D);
                case LEFT -> assertEquals(first.centerX() - first.width() / 2D,
                    second.centerX() - second.width() / 2D, 1.0E-6D);
                case RIGHT -> assertEquals(first.centerX() + first.width() / 2D,
                    second.centerX() + second.width() / 2D, 1.0E-6D);
            }
        }
    }

    @Test
    void wrappedNamedSpansKeepTheirSourceOffsetsAndNativeRows() {
        ParticleTextLayout.TextStyle style = new ParticleTextLayout.TextStyle(24, IconTextAlignment.LEFT);
        ParticleText.Rendered rendered = ParticleText.render("ABC <particles:word>DEF</particles>", value -> value);
        ParticleRect projection = ParticleTextLayout.styledTextBounds(rendered.text(), 1D, style);
        HologramBoxLayout nativeLayout = HologramBoxLayout.measure(rendered.text(), 24, HologramBox.defaults());
        List<ParticleRect> lines = ParticleTextLayout.styledLineBounds(rendered.text(), 1D, style);
        List<ParticleRect> letters = ParticleTextLayout.styledBounds(rendered, "word", 1D, true, style);

        assertEquals(2, lines.size());
        assertEquals(3, letters.size());
        assertEquals(nativeLayout.textHeight() * 0.025D, projection.height(), 1.0E-6D);
        assertEquals((nativeLayout.textWidth()) * 0.025D, projection.width(), 1.0E-6D);
        for (ParticleRect letter : letters) {
            assertEquals(lines.get(1).centerY(), letter.centerY(), 1.0E-6D);
        }
    }
}
