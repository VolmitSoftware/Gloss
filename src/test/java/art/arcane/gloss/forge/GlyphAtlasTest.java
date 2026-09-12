package art.arcane.gloss.forge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlyphAtlasTest {
    private static final String DOC = """
        {"schemaVersion":1,"revision":1,"glyphs":[
          {"id":"logo","image":"brand/logo.png","height":32,"ascent":28,"fallback":"[LOGO]"}],
         "space":{"enabled":false}}
        """;

    @TempDir
    Path folder;

    private GlyphAtlas atlas;

    @AfterEach
    void cleanUp() {
        if (atlas != null) {
            atlas.uninstall();
        }
    }

    private void install() {
        GlyphLedger ledger = GlyphLedger.load(folder.resolve("ledger.json"), GlyphLedger.DEFAULT_BASE);
        GlyphRegistry registry = GlyphRegistry.build(Map.of("brand", GlyphDoc.parse("brand.json", DOC)), ledger,
            imagePath -> new int[]{64, 64});
        atlas = new GlyphAtlas(() -> registry, new LayoutFunctions(() -> registry, FontMetrics.load()));
        atlas.install();
    }

    @Test
    void withoutAnInstalledAtlasNoImageHasAGlyph() {
        assertTrue(GlyphAtlas.glyphFor("brand/logo.png").isEmpty());
        assertTrue(GlyphAtlas.lookup("brand/logo.png").isEmpty());
    }

    @Test
    void aDeclaredImageResolvesToItsGlyphString() {
        install();

        assertEquals("<font:gloss:glyphs>"
                + GlyphAtlas.lookup("brand/logo.png").orElseThrow().character() + "</font>",
            GlyphAtlas.glyphFor("brand/logo.png").orElseThrow());
        assertEquals(32, GlyphAtlas.lookup("brand/logo.png").orElseThrow().height());
    }

    @Test
    void anUndeclaredImageIsLeftToTheRasterPath() {
        install();

        assertTrue(GlyphAtlas.glyphFor("icons/coin.png").isEmpty());
        assertTrue(GlyphAtlas.glyphFor(null).isEmpty());
    }

    @Test
    void uninstallingRestoresTheRasterPathForEveryImage() {
        install();
        atlas.uninstall();

        assertTrue(GlyphAtlas.glyphFor("brand/logo.png").isEmpty());
    }
}
