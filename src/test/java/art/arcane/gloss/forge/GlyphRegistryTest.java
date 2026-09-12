package art.arcane.gloss.forge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlyphRegistryTest {
    private static final GlyphRegistry.ImageProbe SQUARE_16 = imagePath -> new int[]{16, 16};

    @TempDir
    Path folder;

    private GlyphLedger ledger() {
        return GlyphLedger.load(folder.resolve("ledger.json"), GlyphLedger.DEFAULT_BASE);
    }

    @Test
    void resolvesGlyphsOverlaysAndEmojiTriggers() {
        GlyphDoc doc = GlyphDoc.parse("brand.json", """
            {"schemaVersion":1,"revision":1,"glyphs":[
              {"id":"coin","image":"icons/coin.png","height":8,"ascent":7,"fallback":"$"},
              {"id":"heart","emoji":"heart","image":"icons/heart.png","height":8,"ascent":7}],
             "space":{"enabled":true,"range":[-4,4]},
             "overlays":[{"id":"hudframe","image":"hud/frame.png","height":64,"ascent":60,"anchor":"bottom"}]}
            """);

        GlyphRegistry registry = GlyphRegistry.build(Map.of("brand", doc), ledger(), SQUARE_16);

        assertEquals("gloss", registry.namespace());
        assertEquals("glyphs", registry.font());
        assertEquals("$", registry.glyph("coin").orElseThrow().fallback());
        assertEquals(9, registry.glyph("coin").orElseThrow().widthPx());
        assertEquals("hudframe", registry.overlay("hudframe").orElseThrow().id());
        assertTrue(registry.overlay("hudframe").orElseThrow().overlay());
        assertEquals("heart", registry.emojiGlyph("heart").orElseThrow());
        assertTrue(registry.glyph("hudframe").isEmpty());
        assertEquals("coin", registry.byImage("icons/coin.png").orElseThrow().id());
        assertFalse(registry.isEmpty());
    }

    @Test
    void allocatesOneCodepointPerSheetCell() {
        GlyphDoc doc = GlyphDoc.parse("meter.json", """
            {"schemaVersion":1,"revision":1,"glyphs":[
              {"id":"bar","image":"hud/bar.png","height":8,"frames":4}],"space":{"enabled":false}}
            """);

        GlyphRegistry.ResolvedGlyph bar = GlyphRegistry
            .build(Map.of("meter", doc), ledger(), imagePath -> new int[]{32, 8})
            .glyph("bar").orElseThrow();

        assertEquals(4, bar.frames());
        assertEquals(4, bar.codepoints().size());
        assertEquals(9, bar.widthPx());
        assertEquals(bar.character(0), bar.character());
        assertTrue(bar.character(3).codePointAt(0) > bar.character(0).codePointAt(0));
    }

    @Test
    void spaceCodepointsCoverTheUnionOfEveryDocumentRange() {
        GlyphDoc narrow = GlyphDoc.parse("a.json",
            "{\"schemaVersion\":1,\"revision\":1,\"glyphs\":[],\"space\":{\"enabled\":true,\"range\":[-2,2]}}");
        GlyphDoc wide = GlyphDoc.parse("b.json",
            "{\"schemaVersion\":1,\"revision\":1,\"glyphs\":[],\"space\":{\"enabled\":true,\"range\":[-8,1]}}");

        GlyphRegistry.Spaces spaces = GlyphRegistry
            .build(Map.of("a", narrow, "b", wide), ledger(), SQUARE_16).spaces();

        assertTrue(spaces.enabled());
        assertEquals(-8, spaces.minimum());
        assertEquals(2, spaces.maximum());
        assertEquals(11, spaces.codepoints().size());
        assertTrue(spaces.covers(-8));
        assertFalse(spaces.covers(3));
    }

    @Test
    void refusesAnIdDeclaredByTwoDocuments() {
        GlyphDoc first = GlyphDoc.parse("a.json",
            "{\"schemaVersion\":1,\"revision\":1,\"glyphs\":[{\"id\":\"coin\",\"image\":\"a.png\"}]}");
        GlyphDoc second = GlyphDoc.parse("b.json",
            "{\"schemaVersion\":1,\"revision\":1,\"glyphs\":[{\"id\":\"coin\",\"image\":\"b.png\"}]}");
        GlyphLedger ledger = ledger();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> GlyphRegistry.build(Map.of("a", first, "b", second), ledger, SQUARE_16));

        assertTrue(failure.getMessage().contains("coin"), failure.getMessage());
    }

    @Test
    void refusesTwoDocumentsThatTargetDifferentFonts() {
        GlyphDoc first = GlyphDoc.parse("a.json",
            "{\"schemaVersion\":1,\"revision\":1,\"font\":\"glyphs\",\"glyphs\":[]}");
        GlyphDoc second = GlyphDoc.parse("b.json",
            "{\"schemaVersion\":1,\"revision\":1,\"font\":\"other\",\"glyphs\":[]}");
        GlyphLedger ledger = ledger();

        assertThrows(IllegalArgumentException.class,
            () -> GlyphRegistry.build(Map.of("a", first, "b", second), ledger, SQUARE_16));
    }

    @Test
    void declaredWidthsFeedTheFontMetricsTable() {
        GlyphDoc doc = GlyphDoc.parse("brand.json", """
            {"schemaVersion":1,"revision":1,"glyphs":[
              {"id":"logo","image":"brand/logo.png","height":32,"ascent":28,"width":40}],
             "space":{"enabled":true,"range":[-1,1]}}
            """);

        GlyphRegistry registry = GlyphRegistry.build(Map.of("brand", doc), ledger(), SQUARE_16);
        Map<Integer, Integer> widths = registry.declaredWidths();

        int logo = registry.glyph("logo").orElseThrow().codepoints().getFirst();
        assertEquals(40, widths.get(logo));
        assertEquals(-1, widths.get(registry.spaces().codepoint(-1)));
    }

    @Test
    void noDocumentsResolveToTheEmptyRegistry() {
        assertTrue(GlyphRegistry.build(Map.of(), ledger(), SQUARE_16).isEmpty());
    }
}
