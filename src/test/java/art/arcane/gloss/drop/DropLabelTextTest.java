package art.arcane.gloss.drop;

import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.particle.ParticleText;
import art.arcane.gloss.text.TextPipeline;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DropLabelTextTest {
    @Test
    void authoredLabelsKeepViewerExpressionsAndParticleSpansForTheEngine() {
        List<String> authored = List.of("<particles:name>{{ player.name }}</particles>");
        List<Write> writes = new ArrayList<>();
        DropNameService.applyLabelText(target(writes), new RealDropService.Label(authored, List.of("")));

        assertEquals(List.of(new Write("setLines", authored)), writes);
        ParticleText.Rendered rendered = ParticleText.render(writes.getFirst().lines().getFirst(),
            text -> text.replace("{{ player.name }}", "Viewer"));
        assertEquals("Viewer", rendered.text());
        assertEquals(List.of(new ParticleText.Span("name", 0, 6)), rendered.spans());
    }

    @Test
    void nativeNamesDoNotEvaluateViewerInputsWithoutAViewer() {
        List<String> authored = List.of("AUTHOR {{ player.name }} 3x Stone", "%player_name% Stone",
            "{{ papiNumber('player_level') }} Stone", "|viewer-name| Stone");
        for (String source : authored) {
            assertEquals("3x Stone", DropNameService.renderNativeName(source, "3x Stone", input -> {
                throw new AssertionError("Viewer input reached the global renderer: " + input);
            }));
        }
    }

    @Test
    void nativeFallbackItemNamesRemainLiteralAndSharedFormatsStillRender() {
        TextPipeline pipeline = new TextPipeline(null);
        String itemName = "3x <red>{{ player.name }}</red>";
        assertEquals(itemName, DropNameService.renderNativeName("{{ player.name }}", itemName,
            pipeline::renderStatic));
        assertEquals("§a3x Stone", DropNameService.renderNativeName("&a{{ 1 + 2 }}x Stone", "fallback",
            pipeline::renderStatic));
    }

    @Test
    void renderedOnlyNamesUseTheLiteralTextPath() {
        String literal = "<red>{{ player.name }}</red> |animation.fast|";
        List<Write> writes = new ArrayList<>();
        DropNameService.applyLabelText(target(writes), RealDropService.Label.rendered(literal));

        assertEquals(List.of(new Write("setRenderedLines", List.of(literal))), writes);
    }

    @SuppressWarnings("unchecked")
    private static TemporaryHologram target(List<Write> writes) {
        return (TemporaryHologram) Proxy.newProxyInstance(TemporaryHologram.class.getClassLoader(),
            new Class<?>[]{TemporaryHologram.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("setLines") || method.getName().equals("setRenderedLines")) {
                    writes.add(new Write(method.getName(), List.copyOf((List<String>) arguments[0])));
                    return null;
                }
                throw new UnsupportedOperationException(method.getName());
            });
    }

    private record Write(String method, List<String> lines) {
    }
}
