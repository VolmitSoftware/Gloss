package art.arcane.gloss.forge;

import art.arcane.gloss.expr.ExprFunctionRegistry;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayoutFunctionsTest {
    private static final String DOC = """
        {"schemaVersion":1,"revision":1,"namespace":"gloss","font":"glyphs","glyphs":[
          {"id":"coin","image":"icons/coin.png","height":8,"ascent":7,"fallback":"$","width":9},
          {"id":"bar","image":"hud/bar.png","height":8,"ascent":7,"frames":2,"fallback":"|","width":9}],
         "space":{"enabled":true,"range":[-16,16]},
         "overlays":[{"id":"hudframe","image":"hud/frame.png","height":64,"ascent":60,"anchor":"bottom"}]}
        """;

    @TempDir
    Path folder;

    private final ExprFunctionRegistry registry = new ExprFunctionRegistry();
    private GlyphRegistry glyphs;
    private LayoutFunctions functions;

    private static ExprScope scope(boolean packLoaded) {
        Map<String, Object> variables = Map.of("pack.loaded", packLoaded);
        return new ExprScope() {
            @Override
            public Object variable(String dottedName) {
                return variables.get(dottedName);
            }

            @Override
            public Object call(String name, List<Object> args) {
                return null;
            }
        };
    }

    private void install() {
        GlyphLedger ledger = GlyphLedger.load(folder.resolve("ledger.json"), GlyphLedger.DEFAULT_BASE);
        glyphs = GlyphRegistry.build(Map.of("brand", GlyphDoc.parse("brand.json", DOC)), ledger,
            imagePath -> new int[]{8, 8});
        functions = new LayoutFunctions(() -> glyphs, FontMetrics.load());
        functions.register(registry);
    }

    @AfterEach
    void cleanUp() {
        for (String name : LayoutFunctions.NAMES) {
            ExprFunctionRegistry.global().unregister(name);
        }
    }

    private String tagged(String id) {
        GlyphRegistry.ResolvedGlyph glyph = glyphs.glyph(id).orElseGet(() -> glyphs.overlay(id).orElseThrow());
        return "<font:gloss:glyphs>" + glyph.character() + "</font>";
    }

    @Test
    void glyphEmitsTheFontTagForPackViewersAndTheFallbackForEveryoneElse() {
        install();

        assertEquals(tagged("coin"), registry.call(scope(true), "glyph", List.of("coin")));
        assertEquals("$", registry.call(scope(false), "glyph", List.of("coin")));
        assertEquals("", registry.call(scope(true), "glyph", List.of("missing")));
        assertEquals("", registry.call(scope(false), "glyph", List.of("missing")));
    }

    @Test
    void overlayReadsTheOverlayListOnly() {
        install();

        assertEquals(tagged("hudframe"), registry.call(scope(true), "overlay", List.of("hudframe")));
        assertEquals("", registry.call(scope(true), "overlay", List.of("coin")));
        assertEquals("", registry.call(scope(false), "overlay", List.of("hudframe")));
    }

    @Test
    void shiftEmitsSpaceGlyphsOnlyForPackViewers() {
        install();

        String left = (String) registry.call(scope(true), "shift", List.of(-4.0D));
        assertTrue(left.startsWith("<font:gloss:glyphs>"), left);
        assertEquals("", registry.call(scope(false), "shift", List.of(-4.0D)));
        assertEquals("", registry.call(scope(true), "shift", List.of(0.0D)));
    }

    @Test
    void shiftComposesWidthsBeyondTheDeclaredRange() {
        install();

        String far = (String) registry.call(scope(true), "shift", List.of(40.0D));
        int characters = far.replace("<font:gloss:glyphs>", "").replace("</font>", "").codePointCount(0,
            far.replace("<font:gloss:glyphs>", "").replace("</font>", "").length());

        assertEquals(3, characters, far);
    }

    @Test
    void atPlacesTextAtAPixelOffsetAndRestoresTheCursor() {
        install();

        String placed = (String) registry.call(scope(true), "at", List.of(20.0D, "Hi"));
        assertTrue(placed.contains("Hi"), placed);
        assertTrue(placed.indexOf("Hi") > 0, placed);
        assertTrue(placed.endsWith("</font>"), placed);
        assertEquals("Hi", registry.call(scope(false), "at", List.of(20.0D, "Hi")));
    }

    @Test
    void meterRepeatsTheFilledAndEmptyCellsOverTheGlyphFrames() {
        install();

        GlyphRegistry.ResolvedGlyph bar = glyphs.glyph("bar").orElseThrow();
        String full = (String) registry.call(scope(true), "meter", List.of("bar", 2.0D, 2.0D));
        String empty = (String) registry.call(scope(true), "meter", List.of("bar", 0.0D, 2.0D));

        assertEquals("<font:gloss:glyphs>" + bar.character(1).repeat(2) + "</font>", full);
        assertEquals("<font:gloss:glyphs>" + bar.character(0).repeat(2) + "</font>", empty);
        assertEquals("||", registry.call(scope(false), "meter", List.of("bar", 2.0D, 2.0D)));
        assertEquals("", registry.call(scope(false), "meter", List.of("bar", 0.0D, 2.0D)));
    }

    @Test
    void meterClampsOutOfRangeValuesAndRefusesAZeroMaximum() {
        install();

        GlyphRegistry.ResolvedGlyph bar = glyphs.glyph("bar").orElseThrow();
        assertEquals("<font:gloss:glyphs>" + bar.character(1).repeat(2) + "</font>",
            registry.call(scope(true), "meter", List.of("bar", 9.0D, 2.0D)));
        assertEquals("<font:gloss:glyphs>" + bar.character(0).repeat(2) + "</font>",
            registry.call(scope(true), "meter", List.of("bar", -3.0D, 2.0D)));
        assertThrows(RuntimeException.class, () -> registry.call(scope(true), "meter", List.of("bar", 1.0D, 0.0D)));
    }

    @Test
    void everyFunctionRegistersInTheGlobalRegistryWithASignature() {
        install();
        functions.register(ExprFunctionRegistry.global());

        for (String name : LayoutFunctions.NAMES) {
            assertTrue(ExprFunctions.isSupported(name), name);
        }
        assertEquals(ExprFunctionRegistry.Kind.STRING, ExprFunctionRegistry.global().find("glyph").returns());
        assertEquals(List.of(ExprFunctionRegistry.Kind.NUMBER, ExprFunctionRegistry.Kind.STRING),
            ExprFunctionRegistry.global().find("at").parameters());
        assertEquals(List.of(ExprFunctionRegistry.Kind.STRING, ExprFunctionRegistry.Kind.NUMBER,
            ExprFunctionRegistry.Kind.NUMBER), ExprFunctionRegistry.global().find("meter").parameters());
    }

    @Test
    void withoutAnyGlyphsEveryFunctionIsInert() {
        functions = new LayoutFunctions(() -> GlyphRegistry.EMPTY, FontMetrics.load());
        functions.register(registry);

        assertEquals("", registry.call(scope(true), "glyph", List.of("coin")));
        assertEquals("", registry.call(scope(true), "shift", List.of(-4.0D)));
        assertEquals("Hi", registry.call(scope(true), "at", List.of(20.0D, "Hi")));
    }
}
