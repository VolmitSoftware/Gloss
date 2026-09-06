package art.arcane.gloss.hologram;

import art.arcane.gloss.api.HologramBox;
import art.arcane.gloss.api.IconBillboard;
import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramDocTest {
    @Test
    void parseReadsTheCurrentStyleBoxAndVectorAnchor() {
        HologramDoc doc = HologramDoc.parse("spawn-info.json", """
            {
              "schemaVersion": 3, "revision": 6,
              "anchor": {"world": "world_nether", "position": [12.5, 64.0, -7.25]},
              "lines": ["&dWelcome", "&7Line two"],
              "style": {"billboard": "horizontal", "seeThrough": true, "scaleX": 2.5, "scaleY": 0.5, "scaleZ": 4},
              "box": {"enabled": true, "padding": 8, "borderWidth": 3, "backgroundArgb": "#00334455", "borderArgb": "#FF123456"},
              "yaw": -30, "pitch": 15
            }
            """);

        assertEquals(HologramDoc.CURRENT_SCHEMA_VERSION, doc.schemaVersion());
        assertEquals(6L, doc.revision());
        assertEquals("world_nether", doc.anchor().world());
        assertEquals(new Vector(12.5D, 64.0D, -7.25D), doc.anchor().position());
        assertEquals(List.of("&dWelcome", "&7Line two"), doc.lines());
        assertTrue(doc.style().seeThrough());
        assertEquals(2.5F, doc.style().scaleX());
        assertEquals(0.5F, doc.style().scaleY());
        assertEquals(4F, doc.style().scaleZ());
        assertEquals(IconBillboard.HORIZONTAL, doc.style().billboard());
        assertTrue(doc.box().enabled());
        assertEquals(0x00334455, doc.box().backgroundArgb().argb());
        assertEquals(0xFF123456, doc.box().borderArgb().argb());
        assertEquals(-30D, doc.yaw());
        assertEquals(15D, doc.pitch());
    }

    @Test
    void particleLayerVectorsParseFromTheCanonicalArrayShape() {
        HologramDoc doc = HologramDoc.parse("particles.json", """
            {
              "schemaVersion": 3, "revision": 1,
              "anchor": {"world": "world", "position": [0, 64, 0]}, "lines": ["line"],
              "particleLayers": [{
                "id": "underline", "target": {"scope": "local"},
                "geometry": {"type": "line", "from": [-1, -0.2, 0], "to": [1, -0.2, 0]},
                "placement": {"layer": "behind", "depth": 0.05, "offset": [0.1, 0.2, 0.3]},
                "particle": {"key": "minecraft:soul"}
              }]
            }
            """);
        assertEquals(new Vector(-1D, -0.2D, 0D), doc.particleLayers().getFirst().geometry().from());
        assertEquals(new Vector(1D, -0.2D, 0D), doc.particleLayers().getFirst().geometry().to());
        assertEquals(new Vector(0.1D, 0.2D, 0.3D), doc.particleLayers().getFirst().placement().offset());
    }

    @Test
    void gsonRoundTripPreservesAllFields() {
        HologramDoc original = new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 3L,
            new HologramDoc.Anchor("world", new Vector(0D, -32.5D, 1000000.125D)),
            List.of("plain", "", "&x&f&f&0&0&f&fhex"),
            IconDisplayStyle.defaults().withScale(3.75F, 2F, 4F),
            new HologramBox(true, 8, 2, null, null), -135.5D, 12.25D, List.of(), null);
        String json = DocumentParsers.GSON.toJson(original);
        assertEquals(original, HologramDoc.parse("arena.json", json));
        assertTrue(json.replaceAll("\\s", "").contains("\"position\":[0.0,-32.5,1000000.125]"));
    }

    @Test
    void omittedStyleAndBoxUseHologramDefaults() {
        HologramDoc doc = HologramDoc.parse("defaults.json", """
            {"schemaVersion":3,"revision":1,"anchor":{"world":"world","position":[0,0,0]},"lines":["x"]}
            """);
        assertEquals(IconDisplayStyle.hologramDefaults(), doc.style());
        assertEquals(HologramBox.defaults(), doc.box());
        assertEquals(0D, doc.yaw());
        assertEquals(0D, doc.pitch());
    }

    @Test
    void missingAnchorIsRejected() {
        assertThrows(NullPointerException.class, () -> new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION,
            1L, null, List.of("x"), null, null, null, null, List.of(), null));
    }

    @Test
    void invalidEnvelopeIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> HologramDoc.parse("bare.json", "{\"revision\":1,\"lines\":[]}"));
        HologramDoc.Anchor anchor = new HologramDoc.Anchor("world", new Vector());
        assertThrows(IllegalArgumentException.class, () -> new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION,
            0L, anchor, List.of(), null, null, null, null, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION,
            DocumentEnvelope.MAX_SAFE_REVISION + 1L, anchor, List.of(), null, null, null, null, List.of(), null));
    }

    @Test
    void blankAnchorWorldIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new HologramDoc.Anchor("  ", new Vector()));
        assertThrows(IllegalArgumentException.class, () -> new HologramDoc.Anchor(null, new Vector()));
    }

    @Test
    void anchorPositionIsDefensivelyCopied() {
        Vector position = new Vector(1D, 2D, 3D);
        HologramDoc.Anchor anchor = new HologramDoc.Anchor("world", position);
        position.setX(99D);
        anchor.position().setY(99D);
        assertEquals(new Vector(1D, 2D, 3D), anchor.position());
    }

    @Test
    void linesAreImmutableCopiesAndNullLinesAreEmpty() {
        List<String> lines = new ArrayList<>(List.of("one"));
        lines.add(null);
        HologramDoc doc = new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 1L,
            new HologramDoc.Anchor("world", new Vector()), lines, null, null, null, null, List.of(), null);
        lines.clear();
        assertEquals(List.of("one", ""), doc.lines());
        assertThrows(UnsupportedOperationException.class, () -> doc.lines().add("two"));
    }

    @Test
    void withRevisionOnlyChangesTheRevision() {
        HologramDoc doc = new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 1L,
            new HologramDoc.Anchor("world", new Vector(1, 2, 3)), List.of("x"),
            IconDisplayStyle.defaults().withScale(4F, 2F, 3F).withBillboard(IconBillboard.HORIZONTAL),
            new HologramBox(true, 2, 6, null, null), 45D, -30D, List.of(), null);
        HologramDoc bumped = doc.withRevision(2L);
        assertEquals(doc, bumped.withRevision(1L));
        assertEquals(2L, bumped.revision());
    }

    @Test
    void commandBillboardNamesAreNormalizedAndValidated() {
        assertEquals("VERTICAL", HologramDoc.requireBillboard("vertical"));
        assertEquals("FIXED", HologramDoc.requireBillboard("  Fixed  "));
        assertThrows(IllegalArgumentException.class, () -> HologramDoc.requireBillboard("SPIN"));
    }

    @Test
    void orientationBoundsAreEnforced() {
        HologramDoc.Anchor anchor = new HologramDoc.Anchor("world", new Vector());
        HologramDoc edge = new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 1L, anchor, List.of("x"),
            null, null, -180D, 90D, List.of(), null);
        assertEquals(-180D, edge.yaw());
        assertEquals(90D, edge.pitch());
        assertThrows(IllegalArgumentException.class, () -> new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION,
            1L, anchor, List.of("x"), null, null, 181D, 0D, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION,
            1L, anchor, List.of("x"), null, null, 0D, -90.5D, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION,
            1L, anchor, List.of("x"), null, null, Double.NaN, 0D, List.of(), null));
    }

    @Test
    void sharedStyleAndCommandScalesHaveTheSameBounds() {
        assertEquals((float) HologramDoc.MIN_SCALE,
            IconDisplayStyle.defaults().withScale((float) HologramDoc.MIN_SCALE, 1F, 1F).scaleX());
        assertEquals((float) HologramDoc.MAX_SCALE,
            IconDisplayStyle.defaults().withScale((float) HologramDoc.MAX_SCALE, 1F, 1F).scaleX());
        assertThrows(IllegalArgumentException.class, () -> IconDisplayStyle.defaults().withScale(0F, 1F, 1F));
        assertThrows(IllegalArgumentException.class, () -> IconDisplayStyle.defaults().withScale(Float.NaN, 1F, 1F));
    }

    @Test
    void shippedBaselineCarriesTheCurrentDefaultsAndDefaultLine() {
        HologramDoc baseline = HologramBaselines.baseline();
        assertEquals(HologramDoc.CURRENT_SCHEMA_VERSION, baseline.schemaVersion());
        assertEquals(IconDisplayStyle.hologramDefaults(), baseline.style());
        assertEquals(HologramBox.defaults(), baseline.box());
        assertFalse(baseline.lines().isEmpty());
        assertEquals(List.of("&dNew hologram"), HologramBaselines.defaultLines());
    }
}
