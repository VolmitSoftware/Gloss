package art.arcane.gloss.zone;

import art.arcane.gloss.doc.DocumentEnvelope;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ZoneDocTest {
    @Test
    void parsesTheSpecDocument() {
        ZoneDoc doc = ZoneDoc.parse("arena.json", """
            {
              "schemaVersion": 1, "revision": 1,
              "show": "viewer.world == 'world'",
              "shape": { "type": "cuboid", "world": "world", "min": [100, 60, 100], "max": [140, 80, 140] },
              "render": { "mode": "hybrid", "particle": "minecraft:dust", "color": "#FF5555",
                          "spacing": 0.75, "wallMaterial": "minecraft:red_stained_glass",
                          "facingOnly": true, "edgesOnly": false },
              "ambience": { "enabled": true, "particle": "minecraft:ash", "perViewerPerTick": 4,
                            "radius": 12, "when": "world.time > 13000" },
              "toggle": "gloss.zones.show",
              "audience": { "when": "true" }
            }
            """);

        Assertions.assertEquals(DocumentEnvelope.INITIAL_REVISION, doc.revision());
        Assertions.assertEquals("cuboid", doc.shape().type());
        Assertions.assertEquals("hybrid", doc.render().mode());
        Assertions.assertEquals(0xFF5555, doc.render().rgb());
        Assertions.assertTrue(doc.ambience().enabled());
        Assertions.assertEquals(4, doc.ambience().perViewerPerTick());
        Assertions.assertEquals("gloss.zones.show", doc.toggle());
    }

    @Test
    void fillsRenderAndAmbienceDefaults() {
        ZoneDoc doc = ZoneDoc.parse("bare.json", """
            { "schemaVersion": 1, "revision": 1,
              "shape": { "type": "cuboid", "world": "world", "min": [0, 0, 0], "max": [1, 1, 1] } }
            """);

        Assertions.assertEquals("particles", doc.render().mode());
        Assertions.assertFalse(doc.ambience().enabled());
        Assertions.assertEquals("gloss.zones.toggle", doc.toggle());
    }

    @Test
    void refusesAnUnknownRenderMode() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> ZoneDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "render": { "mode": "hologram" },
              "shape": { "type": "cuboid", "world": "world", "min": [0, 0, 0], "max": [1, 1, 1] } }
            """));
    }

    @Test
    void refusesAnUnknownShapeType() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> ZoneDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "shape": { "type": "sphere", "world": "world" } }
            """));
    }

    @Test
    void refusesACuboidWithoutBounds() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> ZoneDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "shape": { "type": "cuboid", "world": "world" } }
            """));
    }

    @Test
    void refusesACylinderWithoutARadius() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> ZoneDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1,
              "shape": { "type": "cylinder", "world": "world", "center": [0, 0], "minY": 0, "maxY": 1 } }
            """));
    }

    @Test
    void refusesAPolygonWithFewerThanThreePoints() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> ZoneDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1,
              "shape": { "type": "polygon", "world": "world", "minY": 0, "maxY": 1,
                         "points": [[0, 0], [1, 1]] } }
            """));
    }

    @Test
    void acceptsARegionShapeWithoutGeometry() {
        ZoneDoc doc = ZoneDoc.parse("spawn.json", """
            { "schemaVersion": 1, "revision": 1,
              "shape": { "type": "region", "plugin": "worldguard", "id": "spawn", "world": "world" } }
            """);

        Assertions.assertEquals("region", doc.shape().type());
        Assertions.assertEquals("spawn", doc.shape().id());
    }

    @Test
    void refusesAnUnsupportedSchemaVersion() {
        IllegalArgumentException failure = Assertions.assertThrows(IllegalArgumentException.class,
            () -> ZoneDoc.parse("bad.json", """
                { "schemaVersion": 4, "revision": 1,
                  "shape": { "type": "cuboid", "world": "world", "min": [0, 0, 0], "max": [1, 1, 1] } }
                """));

        Assertions.assertTrue(DocumentEnvelope.isUnsupportedSchemaVersion(failure));
    }
}
