package art.arcane.gloss.particle;

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

        assertEquals(0.2D, shortText.width(), 1.0E-9D);
        assertEquals(0.6D, longText.width(), 1.0E-9D);
    }
}
