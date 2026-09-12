package art.arcane.gloss.waypoint;

import art.arcane.gloss.api.WaypointSpec;
import art.arcane.gloss.doc.DocumentEnvelope;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WaypointDocTest {
    @Test
    void parsesTheSpecDocument() {
        WaypointDoc doc = WaypointDoc.parse("mill.json", """
            { "schemaVersion": 1, "revision": 1, "show": "true",
              "anchor": { "world": "world", "x": 10, "y": 64, "z": -5 },
              "color": "#55FFFF", "style": "default", "range": 0,
              "audience": { "when": "true" } }
            """);

        Assertions.assertEquals(DocumentEnvelope.INITIAL_REVISION, doc.revision());
        Assertions.assertEquals(0x55FFFF, doc.rgb());
        Assertions.assertEquals(WaypointStyle.DEFAULT, doc.waypointStyle());
        Assertions.assertEquals(0.0D, doc.range());
    }

    @Test
    void readsTheBowtieStyle() {
        WaypointDoc doc = WaypointDoc.parse("boss.json", """
            { "schemaVersion": 1, "revision": 1, "style": "bowtie",
              "anchor": { "world": "world", "x": 0, "y": 0, "z": 0 } }
            """);

        Assertions.assertEquals(WaypointStyle.BOWTIE, doc.waypointStyle());
    }

    @Test
    void defaultsColorToWhiteAndStyleToDefault() {
        WaypointDoc doc = WaypointDoc.parse("plain.json", """
            { "schemaVersion": 1, "revision": 1, "anchor": { "world": "world", "x": 0, "y": 0, "z": 0 } }
            """);

        Assertions.assertEquals(0xFFFFFF, doc.rgb());
        Assertions.assertEquals(WaypointStyle.DEFAULT, doc.waypointStyle());
    }

    @Test
    void refusesAnUnknownStyle() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> WaypointDoc.parse("bad.json", """
            { "schemaVersion": 1, "revision": 1, "style": "spiral",
              "anchor": { "world": "world", "x": 0, "y": 0, "z": 0 } }
            """));
    }

    @Test
    void refusesAnUnsupportedSchemaVersion() {
        IllegalArgumentException failure = Assertions.assertThrows(IllegalArgumentException.class,
            () -> WaypointDoc.parse("bad.json", """
                { "schemaVersion": 3, "revision": 1, "anchor": { "world": "world", "x": 0, "y": 0, "z": 0 } }
                """));

        Assertions.assertTrue(DocumentEnvelope.isUnsupportedSchemaVersion(failure));
    }

    @Test
    void convertsToASpecCarryingTheDocumentId() {
        WaypointSpec spec = WaypointDoc.parse("mill.json", """
            { "schemaVersion": 1, "revision": 1, "color": "#FF0000", "range": 128,
              "anchor": { "world": "world", "x": 1, "y": 2, "z": 3 } }
            """).toSpec("mill");

        Assertions.assertEquals("mill", spec.id());
        Assertions.assertEquals(0xFF0000, spec.color());
        Assertions.assertEquals(128.0D, spec.range());
        Assertions.assertEquals("default", spec.style());
    }
}
