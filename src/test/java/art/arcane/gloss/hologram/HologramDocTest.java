package art.arcane.gloss.hologram;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramDocTest {
    @Test
    void parseReadsTheV2ShapeWithVectorAnchor() {
        String json = """
            {
              "schemaVersion": 1,
              "revision": 6,
              "anchor": {
                "world": "world_nether",
                "position": [12.5, 64.0, -7.25]
              },
              "lines": ["&dWelcome", "&7Line two"]
            }
            """;

        HologramDoc doc = HologramDoc.parse("spawn-info.json", json);

        assertEquals(1, doc.schemaVersion());
        assertEquals(6L, doc.revision());
        assertEquals("world_nether", doc.anchor().world());
        assertEquals(new Vector(12.5D, 64.0D, -7.25D), doc.anchor().position());
        assertEquals(List.of("&dWelcome", "&7Line two"), doc.lines());
    }

    @Test
    void gsonRoundTripPreservesAllFields() {
        HologramDoc original = new HologramDoc(1, 3L,
            new HologramDoc.Anchor("world", new Vector(0.0D, -32.5D, 1000000.125D)),
            List.of("plain", "", "&x&f&f&0&0&f&fhex"));

        HologramDoc decoded = HologramDoc.parse("arena.json", BukkitJson.GSON.toJson(original));

        assertEquals(original, decoded);
    }

    @Test
    void serializedAnchorPositionIsAnArrayTriple() {
        HologramDoc doc = new HologramDoc(1, 1L,
            new HologramDoc.Anchor("world", new Vector(1.0D, 2.0D, 3.0D)), List.of("x"));

        String json = BukkitJson.GSON.toJson(doc);

        assertTrue(json.replaceAll("\\s", "").contains("\"position\":[1.0,2.0,3.0]"));
    }

    @Test
    void legacyFlatShapeWithoutEnvelopeIsRejected() {
        String legacy = "{\"id\":\"shop\",\"world\":\"world\",\"x\":1.5,\"y\":70.0,\"z\":-3.0,\"lines\":[\"&7Buy here\"]}";

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> HologramDoc.parse("shop.json", legacy));

        assertTrue(failure.getMessage().contains("schemaVersion"));
    }

    @Test
    void missingAnchorIsRejected() {
        assertThrows(NullPointerException.class, () -> new HologramDoc(1, 1L, null, List.of("x")));
        assertThrows(RuntimeException.class,
            () -> HologramDoc.parse("bare.json", "{\"schemaVersion\":1,\"revision\":1,\"lines\":[]}"));
    }

    @Test
    void blankAnchorWorldIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new HologramDoc.Anchor("  ", new Vector(0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new HologramDoc.Anchor(null, new Vector(0, 0, 0)));
    }

    @Test
    void anchorPositionIsDefensivelyCopied() {
        Vector position = new Vector(1.0D, 2.0D, 3.0D);
        HologramDoc.Anchor anchor = new HologramDoc.Anchor("world", position);

        position.setX(99.0D);
        anchor.position().setY(99.0D);

        assertEquals(new Vector(1.0D, 2.0D, 3.0D), anchor.position());
    }

    @Test
    void revisionBoundsAreEnforced() {
        HologramDoc.Anchor anchor = new HologramDoc.Anchor("world", new Vector(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new HologramDoc(1, 0L, anchor, List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new HologramDoc(1, DocumentEnvelope.MAX_SAFE_REVISION + 1L, anchor, List.of()));
    }

    @Test
    void linesAreImmutableCopies() {
        HologramDoc doc = new HologramDoc(1, 1L,
            new HologramDoc.Anchor("world", new Vector(0, 0, 0)), List.of("one"));

        assertThrows(UnsupportedOperationException.class, () -> doc.lines().add("two"));
    }

    @Test
    void withRevisionOnlyChangesTheRevision() {
        HologramDoc doc = new HologramDoc(1, 1L,
            new HologramDoc.Anchor("world", new Vector(1, 2, 3)), List.of("x"));

        HologramDoc bumped = doc.withRevision(2L);

        assertEquals(2L, bumped.revision());
        assertEquals(doc.anchor(), bumped.anchor());
        assertEquals(doc.lines(), bumped.lines());
    }

    @Test
    void shippedBaselineParsesAndCarriesTheDefaultLine() {
        HologramDoc baseline = HologramBaselines.baseline();

        assertEquals(1, baseline.schemaVersion());
        assertFalse(baseline.lines().isEmpty());
        assertEquals(List.of("&dNew hologram"), HologramBaselines.defaultLines());
    }
}
