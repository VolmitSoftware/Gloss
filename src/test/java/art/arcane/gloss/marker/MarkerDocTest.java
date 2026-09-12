package art.arcane.gloss.marker;

import art.arcane.gloss.doc.DocumentEnvelope;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class MarkerDocTest {
    private static final String FULL = """
        {
          "schemaVersion": 1, "revision": 1,
          "show": "viewer.world == 'world'",
          "anchor": { "world": "world", "x": 120.5, "y": 71, "z": -33.5 },
          "label": "&6Old Mill",
          "color": "#FFAA00",
          "distanceScale": "clamp(marker.distance / 24, 1, 5)",
          "hideWithin": 4,
          "maxDistance": 256,
          "beam": { "enabled": true, "height": 48, "width": 0.25, "material": "minecraft:yellow_stained_glass" },
          "edge": { "enabled": true, "margin": 0.8, "arrow": "&6>" },
          "trail": { "enabled": false, "particle": "minecraft:end_rod", "spacing": 2, "maxPoints": 48 },
          "audience": { "when": "hasPermission('viewer', 'quests.mill')" },
          "lifetimeTicks": 0
        }
        """;

    @Test
    void parsesTheSpecDocument() {
        MarkerDoc doc = MarkerDoc.parse("mill.json", FULL);

        Assertions.assertEquals(1, doc.schemaVersion());
        Assertions.assertEquals(DocumentEnvelope.INITIAL_REVISION, doc.revision());
        Assertions.assertEquals("world", doc.anchor().world());
        Assertions.assertEquals(0xFFAA00, doc.rgb());
        Assertions.assertEquals(256.0D, doc.maxDistance());
        Assertions.assertTrue(doc.beam().enabled());
        Assertions.assertEquals("minecraft:yellow_stained_glass", doc.beam().material());
        Assertions.assertFalse(doc.trail().enabled());
        Assertions.assertFalse(doc.waypoint());
    }

    @Test
    void convertsToASpecCarryingTheDocumentId() {
        MarkerSpec spec = MarkerDoc.parse("mill.json", FULL).toSpec("mill");

        Assertions.assertEquals("mill", spec.id());
        Assertions.assertEquals("&6Old Mill", spec.label());
        Assertions.assertEquals(0.8D, spec.edge().margin());
        Assertions.assertEquals(4.0D, spec.hideWithin());
    }

    @Test
    void fillsEveryOptionalFieldWithADefault() {
        MarkerDoc doc = MarkerDoc.parse("bare.json", """
            { "schemaVersion": 1, "revision": 1, "anchor": { "world": "world", "x": 0, "y": 0, "z": 0 } }
            """);

        Assertions.assertEquals("", doc.label());
        Assertions.assertEquals(0xFFFFFF, doc.rgb());
        Assertions.assertFalse(doc.beam().enabled());
        Assertions.assertFalse(doc.edge().enabled());
        Assertions.assertFalse(doc.trail().enabled());
        Assertions.assertEquals(0L, doc.lifetimeTicks());
    }

    @Test
    void rejectsAnUnsupportedSchemaVersion() {
        IllegalArgumentException failure = Assertions.assertThrows(IllegalArgumentException.class,
            () -> MarkerDoc.parse("mill.json", """
                { "schemaVersion": 2, "revision": 1, "anchor": { "world": "world", "x": 0, "y": 0, "z": 0 } }
                """));
        Assertions.assertTrue(DocumentEnvelope.isUnsupportedSchemaVersion(failure));
    }

    @Test
    void acceptsAnEntityAnchor() {
        MarkerDoc doc = MarkerDoc.parse("boss.json", """
            { "schemaVersion": 1, "revision": 1,
              "anchor": { "entity": "6a1f9f1e-0000-4000-8000-000000000001" } }
            """);

        Assertions.assertEquals(UUID.fromString("6a1f9f1e-0000-4000-8000-000000000001"), doc.anchor().entity());
        Assertions.assertTrue(doc.anchor().followsEntity());
    }

    @Test
    void acceptsAPlayerAnchor() {
        MarkerDoc doc = MarkerDoc.parse("ally.json", """
            { "schemaVersion": 1, "revision": 1, "anchor": { "player": "Notch" } }
            """);

        Assertions.assertEquals("Notch", doc.anchor().player());
        Assertions.assertTrue(doc.anchor().followsPlayer());
    }

    @Test
    void refusesAnAnchorThatNamesTwoTargets() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> MarkerDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1,
              "anchor": { "player": "Notch", "entity": "6a1f9f1e-0000-4000-8000-000000000001" } }
            """));
    }

    @Test
    void refusesAnAnchorThatNamesNoTarget() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> MarkerDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "anchor": { "world": "world" } }
            """));
    }

    @Test
    void refusesAColorThatIsNotSixHexDigits() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> MarkerDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "color": "orange",
              "anchor": { "world": "world", "x": 0, "y": 0, "z": 0 } }
            """));
    }
}
