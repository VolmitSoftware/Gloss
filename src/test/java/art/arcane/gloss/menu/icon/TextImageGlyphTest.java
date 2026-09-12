package art.arcane.gloss.menu.icon;

import art.arcane.gloss.forge.FontMetrics;
import art.arcane.gloss.forge.GlyphAtlas;
import art.arcane.gloss.forge.GlyphDoc;
import art.arcane.gloss.forge.GlyphLedger;
import art.arcane.gloss.forge.GlyphRegistry;
import art.arcane.gloss.forge.LayoutFunctions;
import art.arcane.gloss.util.common.TextUtils;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The glyph path lifts the 16x16 raster cap for pack viewers. Everyone else keeps the raster, and
 * an image past the cap still reports itself.
 */
class TextImageGlyphTest {
    private static final String DOC = """
        {"schemaVersion":1,"revision":1,"glyphs":[
          {"id":"logo","image":"brand/logo.png","height":64,"ascent":60,"fallback":"[LOGO]"}],
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

    private void installAtlas() {
        GlyphLedger ledger = GlyphLedger.load(folder.resolve("ledger.json"), GlyphLedger.DEFAULT_BASE);
        GlyphRegistry registry = GlyphRegistry.build(Map.of("brand", GlyphDoc.parse("brand.json", DOC)), ledger,
            imagePath -> new int[]{64, 64});
        atlas = new GlyphAtlas(() -> registry, new LayoutFunctions(() -> registry, FontMetrics.load()));
        atlas.install();
    }

    private static BufferedImage image(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    @Test
    void aPackViewerRendersADeclaredImageAsOneGlyphLine() {
        installAtlas();

        Optional<String> glyph = GlyphAtlas.glyphFor("brand/logo.png");
        List<Component> lines = TextImageMenuIcon.glyphLines(glyph.orElseThrow());

        assertEquals(1, lines.size());
        assertEquals(TextUtils.parse(glyph.orElseThrow()), lines.getFirst());
    }

    @Test
    void theGlyphPlaneIsAsTallAsTheGlyphInBlockSpaceLines() {
        installAtlas();

        assertEquals(8.0F, TextImageMenuIcon.glyphPlaneLines(
            GlyphAtlas.lookup("brand/logo.png").orElseThrow().height()));
        assertEquals(1.0F, TextImageMenuIcon.glyphPlaneLines(8));
        assertEquals(1.0F, TextImageMenuIcon.glyphPlaneLines(1));
    }

    @Test
    void aViewerWithoutThePackStillGetsTheRaster() {
        installAtlas();

        List<Component> raster = TextImageRasterCache.lines(image(8, 8), false);

        assertEquals(8, raster.size());
    }

    @Test
    void anOversizeImageStillReportsItselfOnTheRasterPath() {
        assertThrows(IllegalArgumentException.class, () -> TextImageRasterCache.lines(image(32, 32), false));
        int before = TextImageRasterCache.oversizeCount();
        TextImageRasterCache.reportOversize("menus/huge.png", 32, 32);
        TextImageRasterCache.reportOversize("menus/huge.png", 32, 32);

        assertEquals(before + 1, TextImageRasterCache.oversizeCount());
    }

    @Test
    void theOversizeReportPointsAtTheGlyphForge() throws Exception {
        String source = java.nio.file.Files.readString(Path.of(
            "src/main/java/art/arcane/gloss/menu/icon/TextImageRasterCache.java"));

        assertTrue(source.contains("Declare it in a glyphs/ document to render it as one glyph for players "
            + "with the Gloss pack."), source);
    }
}
