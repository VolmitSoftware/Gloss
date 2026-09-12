package art.arcane.gloss.forge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlyphDocTest {
    private static final String SPEC_EXAMPLE = """
        {
          "schemaVersion": 1, "revision": 1,
          "namespace": "gloss",
          "font": "glyphs",
          "glyphs": [
            { "id": "logo", "image": "brand/logo.png", "height": 32, "ascent": 28, "fallback": "[LOGO]" },
            { "id": "coin", "image": "icons/coin.png", "height": 8, "ascent": 7, "fallback": "$" },
            { "id": "heart", "emoji": "heart", "image": "icons/heart.png", "height": 8, "ascent": 7 }
          ],
          "space": { "enabled": true, "range": [-256, 256] },
          "overlays": [ { "id": "hudframe", "image": "hud/frame.png", "height": 64, "ascent": 60, "anchor": "bottom" } ]
        }
        """;

    @Test
    void parsesTheSpecExample() {
        GlyphDoc doc = GlyphDoc.parse("brand.json", SPEC_EXAMPLE);

        assertEquals(GlyphDoc.CURRENT_SCHEMA_VERSION, doc.schemaVersion());
        assertEquals(1L, doc.revision());
        assertEquals("gloss", doc.namespace());
        assertEquals("glyphs", doc.font());
        assertEquals(3, doc.glyphs().size());
        assertEquals("logo", doc.glyphs().getFirst().id());
        assertEquals("brand/logo.png", doc.glyphs().getFirst().image());
        assertEquals(32, doc.glyphs().getFirst().height());
        assertEquals(28, doc.glyphs().getFirst().ascent());
        assertEquals("[LOGO]", doc.glyphs().getFirst().fallback());
        assertEquals("heart", doc.glyphs().get(2).emoji());
        assertTrue(doc.space().enabled());
        assertEquals(-256, doc.space().minimum());
        assertEquals(256, doc.space().maximum());
        assertEquals(1, doc.overlays().size());
        assertEquals("hudframe", doc.overlays().getFirst().id());
        assertEquals("bottom", doc.overlays().getFirst().anchor());
    }

    @Test
    void defaultsFillTheOptionalFields() {
        GlyphDoc doc = GlyphDoc.parse("minimal.json", """
            {"schemaVersion":1,"revision":1,"glyphs":[{"id":"coin","image":"icons/coin.png"}]}
            """);

        assertEquals(GlyphDoc.DEFAULT_NAMESPACE, doc.namespace());
        assertEquals(GlyphDoc.DEFAULT_FONT, doc.font());
        GlyphDoc.Glyph glyph = doc.glyphs().getFirst();
        assertEquals(GlyphDoc.DEFAULT_HEIGHT, glyph.height());
        assertEquals(GlyphDoc.DEFAULT_HEIGHT - 1, glyph.ascent());
        assertEquals("", glyph.fallback());
        assertNull(glyph.emoji());
        assertNull(glyph.width());
        assertEquals(1, glyph.frames());
        assertTrue(doc.overlays().isEmpty());
        assertTrue(doc.space().enabled());
    }

    @Test
    void refusesAnUnsupportedSchemaVersion() {
        assertThrows(IllegalArgumentException.class, () -> GlyphDoc.parse("x.json",
            "{\"schemaVersion\":2,\"revision\":1,\"glyphs\":[]}"));
    }

    @Test
    void refusesBadGlyphIds() {
        assertThrows(IllegalArgumentException.class, () -> GlyphDoc.parse("x.json",
            "{\"schemaVersion\":1,\"revision\":1,\"glyphs\":[{\"id\":\"Coin\",\"image\":\"a.png\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> GlyphDoc.parse("x.json",
            "{\"schemaVersion\":1,\"revision\":1,\"glyphs\":[{\"id\":\"_coin\",\"image\":\"a.png\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> GlyphDoc.parse("x.json",
            "{\"schemaVersion\":1,\"revision\":1,\"glyphs\":[{\"id\":\"coin\",\"image\":\"a.png\"},"
                + "{\"id\":\"coin\",\"image\":\"b.png\"}]}"));
    }

    @Test
    void refusesImagesOutsideTheImagesFolder() {
        assertThrows(IllegalArgumentException.class, () -> GlyphDoc.parse("x.json",
            "{\"schemaVersion\":1,\"revision\":1,\"glyphs\":[{\"id\":\"coin\",\"image\":\"../secret.png\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> GlyphDoc.parse("x.json",
            "{\"schemaVersion\":1,\"revision\":1,\"glyphs\":[{\"id\":\"coin\",\"image\":\"/etc/coin.png\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> GlyphDoc.parse("x.json",
            "{\"schemaVersion\":1,\"revision\":1,\"glyphs\":[{\"id\":\"coin\",\"image\":\"icons/coin.gif\"}]}"));
    }

    @Test
    void refusesHeightAndAscentOutsideTheClientLimits() {
        assertThrows(IllegalArgumentException.class, () -> GlyphDoc.parse("x.json",
            "{\"schemaVersion\":1,\"revision\":1,\"glyphs\":[{\"id\":\"a\",\"image\":\"a.png\",\"height\":257}]}"));
        assertThrows(IllegalArgumentException.class, () -> GlyphDoc.parse("x.json",
            "{\"schemaVersion\":1,\"revision\":1,\"glyphs\":[{\"id\":\"a\",\"image\":\"a.png\",\"height\":0}]}"));
        assertThrows(IllegalArgumentException.class, () -> GlyphDoc.parse("x.json",
            "{\"schemaVersion\":1,\"revision\":1,\"glyphs\":[{\"id\":\"a\",\"image\":\"a.png\",\"height\":8,\"ascent\":9}]}"));
    }

    @Test
    void refusesASpaceRangeWiderThanTheClientAccepts() {
        assertThrows(IllegalArgumentException.class, () -> GlyphDoc.parse("x.json",
            "{\"schemaVersion\":1,\"revision\":1,\"glyphs\":[],\"space\":{\"enabled\":true,\"range\":[-4096,4096]}}"));
        assertThrows(IllegalArgumentException.class, () -> GlyphDoc.parse("x.json",
            "{\"schemaVersion\":1,\"revision\":1,\"glyphs\":[],\"space\":{\"enabled\":true,\"range\":[8,-8]}}"));
    }

    @Test
    void overlayIdsShareTheGlyphNamespace() {
        assertThrows(IllegalArgumentException.class, () -> GlyphDoc.parse("x.json",
            "{\"schemaVersion\":1,\"revision\":1,\"glyphs\":[{\"id\":\"frame\",\"image\":\"a.png\"}],"
                + "\"overlays\":[{\"id\":\"frame\",\"image\":\"b.png\"}]}"));
    }

    @Test
    void shippedSpaceDefaultParsesAndDeclaresNoGlyphs() {
        GlyphDoc doc = GlyphDoc.parse("space.json", """
            {"schemaVersion":1,"revision":1,"namespace":"gloss","font":"glyphs","glyphs":[],
             "space":{"enabled":true,"range":[-256,256]},"overlays":[]}
            """);

        assertEquals(List.of(), doc.glyphs());
        assertTrue(doc.space().enabled());
    }
}
