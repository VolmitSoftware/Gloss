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
    void parseReadsTheSchemaTwoShape() {
        String json = """
            {
              "schemaVersion": 2,
              "revision": 4,
              "prefix": "&b",
              "offset": [0.5, 1.25, -0.5],
              "wordWrapChars": 24,
              "maxAliveMs": 4000,
              "followPlayer": true,
              "hideOwn": false,
              "motion": {
                "translation": {"x": "3 * t", "y": "4 * t * (1 - t)", "z": "0"},
                "scale": {"x": "1 - t", "y": "1 - t", "z": "1"},
                "rotation": {"x": "0", "y": "0", "z": "360 * t"},
                "opacity": "1 - smoothstep(0.7, 1, t)"
              },
              "shimmer": {
                "spawn": true,
                "flyAway": false,
                "color": "#AABBCC",
                "width": 5,
                "durationMs": 900,
                "spawnDelayMs": 50,
                "flyAwayLeadMs": 1100
              },
              "select": {
                "worlds": ["world_*"],
                "groups": ["Staff"],
                "priority": 7
              }
            }
            """;

        BubbleStyleDoc doc = BubbleStyleDoc.parse("staff.json", json);

        assertEquals(2, doc.schemaVersion());
        assertEquals(4L, doc.revision());
        assertEquals("&b", doc.prefix());
        assertEquals(new Vector(0.5D, 1.25D, -0.5D), doc.offset());
        assertEquals(24, doc.wordWrapChars());
        assertEquals(4000L, doc.maxAliveMs());
        assertTrue(doc.followPlayer());
        assertFalse(doc.hideOwn());
        assertEquals("3 * t", doc.motion().translation().x());
        assertEquals("1 - smoothstep(0.7, 1, t)", doc.motion().opacity());
        assertTrue(doc.shimmer().spawn());
        assertFalse(doc.shimmer().flyAway());
        assertEquals("#aabbcc", doc.shimmer().color());
        assertEquals(5, doc.shimmer().width());
        assertEquals(900L, doc.shimmer().durationMs());
        assertEquals(50L, doc.shimmer().spawnDelayMs());
        assertEquals(1100L, doc.shimmer().flyAwayLeadMs());
        assertEquals(List.of("world_*"), doc.select().worlds());
        assertEquals(List.of("staff"), doc.select().groups());
        assertEquals(7, doc.select().priority());
    }

    @Test
    void motionAndSelectUseDefaultsWhenAbsent() {
        String json = """
            {
              "schemaVersion": 2,
              "revision": 1,
              "prefix": "&7",
              "offset": [0.0, 1.0, 0.0],
              "wordWrapChars": 32,
              "maxAliveMs": 5000,
              "followPlayer": true,
              "hideOwn": true
            }
            """;

        BubbleStyleDoc parsed = BubbleStyleDoc.parse("default.json", json);
        assertEquals(BubbleStyleDoc.DEFAULT_TRANSLATION_Y, parsed.motion().translation().y());
        assertEquals(BubbleStyleDoc.DEFAULTS.shimmer(), parsed.shimmer());
        assertNull(parsed.select());
    }

    @Test
    void gsonRoundTripPreservesAllFields() {
        BubbleStyleDoc.Motion motion = new BubbleStyleDoc.Motion(
            new BubbleStyleDoc.Axis("t", "2 * t", "-t"),
            new BubbleStyleDoc.Axis("1", "1", "1"),
            new BubbleStyleDoc.Axis("0", "0", "90 * t"), "1 - t");
        BubbleStyleDoc original = new BubbleStyleDoc(2, 9L, "&d", new Vector(0.0D, 2.0D, 0.0D), 40, 6000L,
            false, true, motion, BubbleStyleDoc.DEFAULTS.shimmer(),
            new BubbleStyleDoc.Select(List.of("hub"), List.of("vip"), 3));

        BubbleStyleDoc decoded = BubbleStyleDoc.parse("vip.json", BukkitJson.GSON.toJson(original));

        assertEquals(original, decoded);
    }

    @Test
    void oldSchemaAndShapeWithoutEnvelopeAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> BubbleStyleDoc.parse("default.json", "{\"schemaVersion\":1,\"revision\":1}"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> BubbleStyleDoc.parse("default.json", "{\"prefix\":\"&7\",\"wordWrapChars\":32}"));
        assertTrue(failure.getMessage().contains("schemaVersion"));
    }

    @Test
    void revisionBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class,
            () -> new BubbleStyleDoc(2, 0L, "&7", null, 32, 5000L, true, true, null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new BubbleStyleDoc(2, DocumentEnvelope.MAX_SAFE_REVISION + 1L, "&7", null, 32, 5000L,
                true, true, null, null, null));
    }

    @Test
    void outOfRangeValuesClamp() {
        BubbleStyleDoc doc = new BubbleStyleDoc(2, 1L, null, null, 1000, 10L, true, true, null, null, null);

        assertEquals("&7", doc.prefix());
        assertEquals(new Vector(0.0D, 1.0D, 0.0D), doc.offset());
        assertEquals(128, doc.wordWrapChars());
        assertEquals(500L, doc.maxAliveMs());
    }

    @Test
    void invalidMotionSourcesAreRejectedAtLoad() {
        assertThrows(IllegalArgumentException.class,
            () -> new BubbleStyleDoc.Motion(new BubbleStyleDoc.Axis("unknown", "0", "0"), null, null, "1"));
        assertThrows(IllegalArgumentException.class,
            () -> new BubbleStyleDoc.Motion(new BubbleStyleDoc.Axis("1 / t", "0", "0"), null, null, "1"));
        assertThrows(IllegalArgumentException.class,
            () -> new BubbleStyleDoc.Motion(new BubbleStyleDoc.Axis("x".repeat(513), "0", "0"), null, null, "1"));
    }

    @Test
    void shimmerPartialShapeUsesDefaultsAndClampsBoundedValues() {
        BubbleStyleDoc parsed = BubbleStyleDoc.parse("default.json", """
            {
              "schemaVersion": 2,
              "revision": 1,
              "shimmer": {
                "color": "#ABCDEF",
                "width": 999,
                "durationMs": 1,
                "spawnDelayMs": -1,
                "flyAwayLeadMs": 999999
              }
            }
            """);

        assertTrue(parsed.shimmer().spawn());
        assertTrue(parsed.shimmer().flyAway());
        assertEquals("#abcdef", parsed.shimmer().color());
        assertEquals(BubbleStyleDoc.Shimmer.MAX_WIDTH, parsed.shimmer().width());
        assertEquals(BubbleStyleDoc.Shimmer.MIN_DURATION_MS, parsed.shimmer().durationMs());
        assertEquals(0L, parsed.shimmer().spawnDelayMs());
        assertEquals(BubbleStyleDoc.Shimmer.MAX_OFFSET_MS, parsed.shimmer().flyAwayLeadMs());
    }

    @Test
    void malformedShimmerColorIsRejectedAtLoad() {
        assertThrows(IllegalArgumentException.class,
            () -> BubbleStyleDoc.parse("default.json", """
                {"schemaVersion":2,"revision":1,"shimmer":{"color":"white"}}
                """));
    }

    @Test
    void selectNormalizesBlankAndCase() {
        BubbleStyleDoc.Select select = new BubbleStyleDoc.Select(List.of(" world ", ""), List.of(" VIP ", " "), 1);

        assertEquals(List.of("world"), select.worlds());
        assertEquals(List.of("vip"), select.groups());
    }
}
