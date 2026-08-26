package art.arcane.gloss.particle;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    private static String substring(ParticleText.Rendered rendered, ParticleText.Span span) {
        return rendered.text().substring(span.start(), span.end());
    }
}
