package art.arcane.gloss.bubble;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BubbleStyleDocTest {
    @Test
    void parseReadsTheV2Shape() {
        String json = """
            {
              "schemaVersion": 1,
              "revision": 4,
              "prefix": "&b",
              "offset": [0.5, 1.25, -0.5],
              "wordWrapChars": 24,
              "lineStaggerTicks": 3,
              "maxAliveMs": 4000,
              "flyAway": false,
              "followPlayer": true,
              "hideOwn": false,
              "select": {
                "worlds": ["world_*"],
                "groups": ["Staff"],
                "priority": 7
              }
            }
            """;

        BubbleStyleDoc doc = BubbleStyleDoc.parse("staff.json", json);

        assertEquals(1, doc.schemaVersion());
        assertEquals(4L, doc.revision());
        assertEquals("&b", doc.prefix());
        assertEquals(new Vector(0.5D, 1.25D, -0.5D), doc.offset());
        assertEquals(24, doc.wordWrapChars());
        assertEquals(3, doc.lineStaggerTicks());
        assertEquals(4000L, doc.maxAliveMs());
        assertFalse(doc.flyAway());
        assertTrue(doc.followPlayer());
        assertFalse(doc.hideOwn());
        assertEquals(List.of("world_*"), doc.select().worlds());
        assertEquals(List.of("staff"), doc.select().groups());
        assertEquals(7, doc.select().priority());
    }

    @Test
    void selectIsOptional() {
        String json = """
            {
              "schemaVersion": 1,
              "revision": 1,
              "prefix": "&7",
              "offset": [0.0, 1.0, 0.0],
              "wordWrapChars": 32,
              "lineStaggerTicks": 5,
              "maxAliveMs": 5000,
              "flyAway": true,
              "followPlayer": true,
              "hideOwn": true
            }
            """;

        assertNull(BubbleStyleDoc.parse("default.json", json).select());
    }

    @Test
    void gsonRoundTripPreservesAllFields() {
        BubbleStyleDoc original = new BubbleStyleDoc(1, 9L, "&d", new Vector(0.0D, 2.0D, 0.0D), 40, 2, 6000L,
            false, false, true, new BubbleStyleDoc.Select(List.of("hub"), List.of("vip"), 3));

        BubbleStyleDoc decoded = BubbleStyleDoc.parse("vip.json", BukkitJson.GSON.toJson(original));

        assertEquals(original, decoded);
    }

    @Test
    void legacyShapeWithoutEnvelopeIsRejected() {
        String legacy = "{\"prefix\":\"&7\",\"wordWrapChars\":32}";

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> BubbleStyleDoc.parse("default.json", legacy));

        assertTrue(failure.getMessage().contains("schemaVersion"));
    }

    @Test
    void revisionBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class,
            () -> new BubbleStyleDoc(1, 0L, "&7", null, 32, 5, 5000L, true, true, true, null));
        assertThrows(IllegalArgumentException.class,
            () -> new BubbleStyleDoc(1, DocumentEnvelope.MAX_SAFE_REVISION + 1L, "&7", null, 32, 5, 5000L,
                true, true, true, null));
    }

    @Test
    void outOfRangeValuesClamp() {
        BubbleStyleDoc doc = new BubbleStyleDoc(1, 1L, null, null, 1000, -3, 10L, true, true, true, null);

        assertEquals("&7", doc.prefix());
        assertEquals(new Vector(0.0D, 1.0D, 0.0D), doc.offset());
        assertEquals(128, doc.wordWrapChars());
        assertEquals(0, doc.lineStaggerTicks());
        assertEquals(500L, doc.maxAliveMs());
    }

    @Test
    void selectNormalizesBlankAndCase() {
        BubbleStyleDoc.Select select = new BubbleStyleDoc.Select(List.of(" world ", ""), List.of(" VIP ", " "), 1);

        assertEquals(List.of("world"), select.worlds());
        assertEquals(List.of("vip"), select.groups());
    }
}
