package art.arcane.gloss.hologram;

import art.arcane.gloss.api.HologramBox;
import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.api.IconArgbColor;
import art.arcane.gloss.api.IconDisplayStyle;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @Test
    void backgroundAndBorderStayBehindTextAcrossScaleAndRotation() {
        HologramBox box = new HologramBox(true, 4, 2, null, null);
        HologramBoxLayout layout = HologramBoxLayout.measure("Sentinel\n20/20 HP\nATK 3 | ARM 2", 16384, box);
        List<HologramPresentation> presentations = List.of(
            HologramPresentation.identity(),
            new HologramPresentation(2, 3, 0.5, 37, 123, 71, 1),
            new HologramPresentation(0.5, 1.5, 2, 290, 40, 185, 1));
        List<IconDisplayStyle> styles = List.of(
            IconDisplayStyle.hologramDefaults(),
            IconDisplayStyle.hologramDefaults().withScale(0.8F, 0.8F, 0.8F),
            IconDisplayStyle.hologramDefaults().withScale(0.5F, 1.7F, 2F));
        assertEquals(5, layout.parts(box).size());
        for (HologramPresentation presentation : presentations) {
            for (IconDisplayStyle style : styles) {
                assertBehindText(layout, box, presentation, style);
            }
        }
    }

    @Test
    void theLayoutKeyMeasuresIdenticallyToTheTextItProjects() {
        HologramBox box = new HologramBox(true, 4, 2, null, null);
        List<String> samples = List.of(
            "plain",
            "\u00a7aHello \u00a7bworld",
            "\u00a7lBold\u00a7r plain",
            "\u00a7x\u00a7f\u00a7f\u00a70\u00a70\u00a70\u00a70gradient",
            "\u00a7ka\u00a7mb\u00a7nc\u00a7od",
            "line one\nline two",
            "trailing \u00a7",
            "\u00a7lbold \u00a7cthen coloured",
            "");
        for (String sample : samples) {
            assertEquals(HologramBoxLayout.measure(sample, 16384, box),
                HologramBoxLayout.measure(HologramBoxLayout.layoutKey(sample), 16384, box),
                "layout key changed the measurement of " + sample);
        }
    }

    @Test
    void colourOnlyChangesShareOneLayoutKeyButBoldAndGlyphsDoNot() {
        assertEquals(HologramBoxLayout.layoutKey("\u00a7aHello"),
            HologramBoxLayout.layoutKey("\u00a7cHello"));
        assertEquals(HologramBoxLayout.layoutKey("\u00a7aHello"),
            HologramBoxLayout.layoutKey("\u00a7x\u00a7f\u00a7f\u00a70\u00a70\u00a70\u00a70Hello"));
        assertNotEquals(HologramBoxLayout.layoutKey("\u00a7aHello"),
            HologramBoxLayout.layoutKey("\u00a7lHello"));
        assertNotEquals(HologramBoxLayout.layoutKey("\u00a7aHello"),
            HologramBoxLayout.layoutKey("\u00a7aHellO"));
    }

    private static void assertBehindText(HologramBoxLayout layout, HologramBox box,
                                         HologramPresentation presentation, IconDisplayStyle style) {
        Quaternionf billboard = new Quaternionf().rotationYXZ(2.3F, -0.7F, 0F);
        Quaternionf presentationRotation = new Quaternionf().rotationXYZ(
            (float) Math.toRadians(presentation.rotationXDegrees()),
            (float) Math.toRadians(presentation.rotationYDegrees()),
            (float) Math.toRadians(presentation.rotationZDegrees()));
        Vector3f front = new Vector3f(0F, 0F, 1F).rotate(presentationRotation).rotate(billboard);
        List<Vector3f> nativeBackgroundCorners = List.of(
            new Vector3f(-0.05F, 0F, -0.00025F),
            new Vector3f(0.075F, 0F, -0.00025F),
            new Vector3f(0.075F, 0.25F, -0.00025F),
            new Vector3f(-0.05F, 0.25F, -0.00025F));
        for (HologramBoxLayout.Part part : layout.parts(box)) {
            Transformation transformation = layout.transform(part, presentation, style);
            for (Vector3f corner : nativeBackgroundCorners) {
                Vector3f point = new Vector3f(corner).rotate(transformation.getRightRotation())
                    .mul(transformation.getScale()).rotate(transformation.getLeftRotation())
                    .add(transformation.getTranslation()).rotate(billboard);
                assertTrue(point.dot(front) < 0F, "Box quad must stay behind the visible text plane");
            }
        }
    }

    private static Vector3f center(Transformation transformation) {
        Vector3f local = new Vector3f(0.0125F, 0.125F, 0F).mul(transformation.getScale());
        transformation.getLeftRotation().transform(local);
        return local.add(transformation.getTranslation());
    }
}
