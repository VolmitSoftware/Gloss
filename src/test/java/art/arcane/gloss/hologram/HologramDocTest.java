package art.arcane.gloss.hologram;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.ShippedResources;
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
    void parseReadsTheCurrentShapeWithVectorAnchor() {
        String json = """
            {
              "schemaVersion": 1,
              "revision": 6,
              "anchor": {
                "world": "world_nether",
                "position": [12.5, 64.0, -7.25]
              },
              "lines": ["&dWelcome", "&7Line two"],
              "scale": 2.5
            }
            """;

        HologramDoc doc = HologramDoc.parse("spawn-info.json", json);

        assertEquals(HologramDoc.CURRENT_SCHEMA_VERSION, doc.schemaVersion());
        assertEquals(6L, doc.revision());
        assertEquals("world_nether", doc.anchor().world());
        assertEquals(new Vector(12.5D, 64.0D, -7.25D), doc.anchor().position());
        assertEquals(List.of("&dWelcome", "&7Line two"), doc.lines());
        assertTrue(doc.seeThrough());
        assertEquals(2.5D, doc.scale());
        assertEquals(HologramDoc.DEFAULT_BILLBOARD, doc.billboard());
        assertEquals(0.0D, doc.yaw());
        assertEquals(0.0D, doc.pitch());
    }

    @Test
    void gsonRoundTripPreservesAllFields() {
        HologramDoc original = new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 3L,
            new HologramDoc.Anchor("world", new Vector(0.0D, -32.5D, 1000000.125D)),
            List.of("plain", "", "&x&f&f&0&0&f&fhex"), false, 3.75D, "FIXED", -135.5D, 12.25D);

        HologramDoc decoded = HologramDoc.parse("arena.json", BukkitJson.GSON.toJson(original));

        assertEquals(original, decoded);
        assertFalse(decoded.seeThrough());
        assertEquals(3.75D, decoded.scale());
        assertEquals("FIXED", decoded.billboard());
        assertEquals(-135.5D, decoded.yaw());
        assertEquals(12.25D, decoded.pitch());
    }

    @Test
    void serializedAnchorPositionIsAnArrayTriple() {
        HologramDoc doc = new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 1L,
            new HologramDoc.Anchor("world", new Vector(1.0D, 2.0D, 3.0D)), List.of("x"), true,
            HologramDoc.DEFAULT_SCALE, HologramDoc.DEFAULT_BILLBOARD, 0.0D, 0.0D);

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
        assertThrows(NullPointerException.class, () -> new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION,
            1L, null, List.of("x"), true, HologramDoc.DEFAULT_SCALE,
            HologramDoc.DEFAULT_BILLBOARD, 0.0D, 0.0D));
        assertThrows(RuntimeException.class,
            () -> HologramDoc.parse("bare.json", "{\"schemaVersion\":1,\"revision\":1,\"scale\":1,\"lines\":[]}"));
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
        assertThrows(IllegalArgumentException.class, () -> new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION,
            0L, anchor, List.of(), true, HologramDoc.DEFAULT_SCALE,
            HologramDoc.DEFAULT_BILLBOARD, 0.0D, 0.0D));
        assertThrows(IllegalArgumentException.class,
            () -> new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION,
                DocumentEnvelope.MAX_SAFE_REVISION + 1L, anchor, List.of(), true,
                HologramDoc.DEFAULT_SCALE, HologramDoc.DEFAULT_BILLBOARD, 0.0D, 0.0D));
    }

    @Test
    void linesAreImmutableCopies() {
        HologramDoc doc = new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 1L,
            new HologramDoc.Anchor("world", new Vector(0, 0, 0)), List.of("one"), true,
            HologramDoc.DEFAULT_SCALE, HologramDoc.DEFAULT_BILLBOARD, 0.0D, 0.0D);

        assertThrows(UnsupportedOperationException.class, () -> doc.lines().add("two"));
    }

    @Test
    void withRevisionOnlyChangesTheRevision() {
        HologramDoc doc = new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 1L,
            new HologramDoc.Anchor("world", new Vector(1, 2, 3)), List.of("x"), false,
            4.0D, "HORIZONTAL", 45.0D, -30.0D);

        HologramDoc bumped = doc.withRevision(2L);

        assertEquals(2L, bumped.revision());
        assertEquals(doc.anchor(), bumped.anchor());
        assertEquals(doc.lines(), bumped.lines());
        assertEquals(doc.seeThrough(), bumped.seeThrough());
        assertEquals(doc.scale(), bumped.scale());
        assertEquals(doc.billboard(), bumped.billboard());
        assertEquals(doc.yaw(), bumped.yaw());
        assertEquals(doc.pitch(), bumped.pitch());
    }

    @Test
    void orientationDefaultsReproduceTodaysCenterBillboard() {
        String json = """
            {
              "schemaVersion": 1,
              "revision": 1,
              "anchor": {"world": "world", "position": [0.0, 0.0, 0.0]},
              "lines": ["&dNew hologram"],
              "seeThrough": true,
              "scale": 1.0
            }
            """;

        HologramDoc doc = HologramDoc.parse("legacy.json", json);

        assertEquals("CENTER", doc.billboard());
        assertEquals(0.0D, doc.yaw());
        assertEquals(0.0D, doc.pitch());
    }

    @Test
    void billboardIsUppercasedAndCheckedAgainstTheFourModes() {
        HologramDoc.Anchor anchor = new HologramDoc.Anchor("world", new Vector(0, 0, 0));

        assertEquals("VERTICAL", new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 1L, anchor,
            List.of("x"), true, HologramDoc.DEFAULT_SCALE, "vertical", 0.0D, 0.0D)
            .billboard());
        assertEquals("FIXED", new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 1L, anchor,
            List.of("x"), true, HologramDoc.DEFAULT_SCALE, "  Fixed  ", 0.0D, 0.0D)
            .billboard());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 1L, anchor, List.of("x"), true,
                HologramDoc.DEFAULT_SCALE, "SPIN", 0.0D, 0.0D));

        assertTrue(failure.getMessage().contains("CENTER, FIXED, HORIZONTAL, VERTICAL"));
        assertTrue(failure.getMessage().contains("SPIN"));
    }

    @Test
    void anglesOutsideTheirRangeAreRejected() {
        HologramDoc.Anchor anchor = new HologramDoc.Anchor("world", new Vector(0, 0, 0));

        assertEquals(-180.0D, new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 1L, anchor,
            List.of("x"), true, HologramDoc.DEFAULT_SCALE, "FIXED", -180.0D, 90.0D).yaw());
        assertEquals(90.0D, new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 1L, anchor,
            List.of("x"), true, HologramDoc.DEFAULT_SCALE, "FIXED", -180.0D, 90.0D).pitch());

        IllegalArgumentException yawFailure = assertThrows(IllegalArgumentException.class,
            () -> new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 1L, anchor, List.of("x"), true,
                HologramDoc.DEFAULT_SCALE, "FIXED", 181.0D, 0.0D));
        assertTrue(yawFailure.getMessage().contains("yaw"));

        IllegalArgumentException pitchFailure = assertThrows(IllegalArgumentException.class,
            () -> new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 1L, anchor, List.of("x"), true,
                HologramDoc.DEFAULT_SCALE, "FIXED", 0.0D, -90.5D));
        assertTrue(pitchFailure.getMessage().contains("pitch"));

        assertThrows(IllegalArgumentException.class,
            () -> new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 1L, anchor, List.of("x"), true,
                HologramDoc.DEFAULT_SCALE, "FIXED", Double.NaN, 0.0D));
    }

    @Test
    void aDocumentWithoutOrientationKeysIsTheSameDocumentAsOneSpellingTheDefaultsOut() {
        String bare = """
            {
              "schemaVersion": 1,
              "revision": 4,
              "anchor": {"world": "world", "position": [1.0, 2.0, 3.0]},
              "lines": ["&dNew hologram"],
              "seeThrough": true,
              "scale": 1.0
            }
            """;
        String spelled = """
            {
              "schemaVersion": 1,
              "revision": 4,
              "anchor": {"world": "world", "position": [1.0, 2.0, 3.0]},
              "lines": ["&dNew hologram"],
              "seeThrough": true,
              "scale": 1.0,
              "billboard": "CENTER",
              "yaw": 0.0,
              "pitch": 0.0
            }
            """;

        assertEquals(HologramDoc.parse("spelled.json", spelled), HologramDoc.parse("bare.json", bare));
    }

    @Test
    void theShippedBaselineStillCarriesNoOrientationKeys() {
        String raw = ShippedResources.readText(HologramBaselines.RESOURCE);

        assertFalse(raw.contains("billboard"));
        assertFalse(raw.contains("yaw"));
        assertFalse(raw.contains("pitch"));

        HologramDoc baseline = HologramBaselines.baseline();

        assertEquals("CENTER", baseline.billboard());
        assertEquals(HologramDoc.DEFAULT_SCALE, baseline.scale());
        assertEquals(0.0D, baseline.yaw());
        assertEquals(0.0D, baseline.pitch());
    }

    @Test
    void shippedBaselineParsesAndCarriesTheDefaultLine() {
        HologramDoc baseline = HologramBaselines.baseline();

        assertEquals(HologramDoc.CURRENT_SCHEMA_VERSION, baseline.schemaVersion());
        assertFalse(baseline.lines().isEmpty());
        assertEquals(List.of("&dNew hologram"), HologramBaselines.defaultLines());
    }

    @Test
    void scaleMustBeExplicitAndWithinTheNativeDisplayRange() {
        HologramDoc.Anchor anchor = new HologramDoc.Anchor("world", new Vector(0, 0, 0));
        String missingScale = """
            {
              "schemaVersion": 1,
              "revision": 1,
              "anchor": {"world": "world", "position": [0.0, 0.0, 0.0]},
              "lines": ["x"]
            }
            """;

        assertEquals(HologramDoc.MIN_SCALE, new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 1L,
            anchor, List.of("x"), true, HologramDoc.MIN_SCALE,
            HologramDoc.DEFAULT_BILLBOARD, 0.0D, 0.0D).scale());
        assertEquals(HologramDoc.MAX_SCALE, new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 1L,
            anchor, List.of("x"), true, HologramDoc.MAX_SCALE,
            HologramDoc.DEFAULT_BILLBOARD, 0.0D, 0.0D).scale());
        assertThrows(IllegalArgumentException.class,
            () -> new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 1L, anchor, List.of("x"), true,
                0.0D, HologramDoc.DEFAULT_BILLBOARD, 0.0D, 0.0D));
        assertThrows(IllegalArgumentException.class,
            () -> new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 1L, anchor, List.of("x"), true,
                Double.NaN, HologramDoc.DEFAULT_BILLBOARD, 0.0D, 0.0D));
        assertThrows(IllegalArgumentException.class,
            () -> HologramDoc.parse("missing-scale.json", missingScale));
    }
}
