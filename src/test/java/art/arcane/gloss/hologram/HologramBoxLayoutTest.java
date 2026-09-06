package art.arcane.gloss.hologram;

import art.arcane.gloss.api.HologramBox;
import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.api.IconArgbColor;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramBoxLayoutTest {
    @Test
    void boxDefaultsAreDisabledAndRejectInvalidGeometry() {
        HologramBox box = new HologramBox(null, null, null, null, null);
        assertFalse(box.enabled());
        assertEquals(4, box.padding());
        assertEquals(1, box.borderWidth());
        assertEquals("#B31B1B22", box.backgroundArgb().hex());
        assertThrows(IllegalArgumentException.class, () -> new HologramBox(true, -1, 1, null, null));
        assertThrows(IllegalArgumentException.class, () -> new HologramBox(true, 1, 17, null, null));
    }

    @Test
    void frameHasUniformPixelPaddingAroundMultilineNativeFontBounds() {
        HologramBox box = new HologramBox(true, 4, 2, null, null);
        HologramBoxLayout narrow = HologramBoxLayout.measure("iii", 16384, box);
        HologramBoxLayout wide = HologramBoxLayout.measure("WWW", 16384, box);
        HologramBoxLayout bold = HologramBoxLayout.measure("§lWWW", 16384, box);
        HologramBoxLayout multiline = HologramBoxLayout.measure("iii\nWWW", 16384, box);
        assertTrue(wide.textWidth() > narrow.textWidth());
        assertEquals(wide.textWidth() + 3, bold.textWidth());
        assertEquals(wide.textWidth(), multiline.textWidth());
        assertEquals(19, multiline.textHeight());
        assertEquals(multiline.textWidth() + 8, multiline.panelWidth());
        assertEquals(multiline.textHeight() + 8, multiline.panelHeight());
        assertEquals(multiline.panelWidth() + 4, multiline.frameWidth());
        assertEquals(multiline.panelHeight() + 4, multiline.frameHeight());
    }

    @Test
    void perimeterLeavesThePanelInteriorEmptyAndTransformsEveryEdge() {
        HologramBox box = new HologramBox(true, 8, 3, IconArgbColor.TRANSPARENT, null);
        HologramBoxLayout layout = HologramBoxLayout.measure("Health\nArmor", 16384, box);
        HologramPresentation presentation = new HologramPresentation(2, 3, 1, 0, 0, 45, 1);
        List<HologramBoxLayout.Part> parts = layout.parts(box);
        assertEquals(4, parts.size());
        for (HologramBoxLayout.Part part : parts) {
            assertTrue(Math.abs(part.x()) - part.width() / 2F >= layout.panelWidth() / 2F
                || Math.abs(part.y()) - part.height() / 2F >= layout.panelHeight() / 2F);
            assertEquals(3, Math.min(part.width(), part.height()));
            Vector3f transformed = center(layout.transform(part, presentation, null));
            assertTrue(Float.isFinite(transformed.x));
            assertTrue(Float.isFinite(transformed.y));
        }
        assertEquals(parts.get(0).y(), -parts.get(1).y());
        assertEquals(parts.get(2).x(), -parts.get(3).x());
        assertEquals(layout.frameWidth(), parts.getFirst().width());
        assertTrue(layout.parts(new HologramBox(true, 0, 0, IconArgbColor.TRANSPARENT, null)).isEmpty());
    }

    @Test
    void lineWidthWrapsAtWordsBeforeSplittingGlyphs() {
        HologramBox box = HologramBox.defaults();
        HologramBoxLayout word = HologramBoxLayout.measure("WW", 16384, box);
        HologramBoxLayout wrapped = HologramBoxLayout.measure("WW WW", 24, box);
        assertEquals(word.textWidth(), wrapped.textWidth());
        assertEquals(19, wrapped.textHeight());
    }

    private static Vector3f center(Transformation transformation) {
        Vector3f local = new Vector3f(0.0125F, 0.125F, 0F).mul(transformation.getScale());
        transformation.getLeftRotation().transform(local);
        return local.add(transformation.getTranslation());
    }
}
