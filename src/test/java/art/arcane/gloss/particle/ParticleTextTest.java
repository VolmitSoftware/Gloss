package art.arcane.gloss.particle;

import art.arcane.gloss.util.common.TextUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParticleTextTest {
    @Test
    void extractsRepeatedNamedSpansBeforeRendering() {
        ParticleText.Rendered rendered = ParticleText.render(
            "this is: <particles:green-word>&4GREEN</particles> and <particles:green-word>LIME</particles>",
            value -> value.replace("GREEN", "BRIGHT GREEN"));

        assertEquals("this is: &4BRIGHT GREEN and LIME", rendered.text());
        assertEquals(2, rendered.named("green-word").size());
        assertEquals("&4BRIGHT GREEN", substring(rendered, rendered.named("green-word").get(0)));
        assertEquals("LIME", substring(rendered, rendered.named("green-word").get(1)));
    }

    @Test
    void rejectsMalformedOrNestedSpans() {
        assertThrows(IllegalArgumentException.class,
            () -> ParticleText.parse("<particles:a>x"));
        assertThrows(IllegalArgumentException.class,
            () -> ParticleText.parse("</particles>"));
        assertThrows(IllegalArgumentException.class,
            () -> ParticleText.parse("<particles:a><particles:b>x</particles></particles>"));
    }

    @Test
    void rendererCannotInjectAuthoredParticleTags() {
        ParticleText.Rendered rendered = ParticleText.render("{value}",
            ignored -> "<particles:injected>text</particles>");

        assertEquals("<particles:injected>text</particles>", rendered.text());
        assertEquals(List.of(), rendered.spans());
    }

    @Test
    void richFormattingResolvesBeforeParticleGeometryAndPreservesGradientSpans() {
        ParticleText.Rendered rendered = ParticleText.renderLegacy(
            "<gradient:#ff0000:#0000ff>A<particles:name>BC</particles>D</gradient>",
            UnaryOperator.identity());

        assertEquals("ABCD", TextUtils.content(TextUtils.parseLegacy(rendered.text())));
        assertEquals(1, rendered.spans().size());
        assertEquals("BC", TextUtils.content(TextUtils.parseLegacy(substring(rendered, rendered.spans().getFirst()))));
        assertEquals(ParticleTextLayout.textBounds("ABCD", 1D), ParticleTextLayout.textBounds(rendered.text(), 1D));
    }

    private static String substring(ParticleText.Rendered rendered, ParticleText.Span span) {
        return rendered.text().substring(span.start(), span.end());
    }
}
