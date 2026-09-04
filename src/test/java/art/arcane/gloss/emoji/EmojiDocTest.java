package art.arcane.gloss.emoji;

import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmojiDocTest {
    @Test
    void parseReadsTheV2Shape() {
        String json = """
            {
              "schemaVersion": 1,
              "revision": 3,
              "trigger": "<3",
              "emoji": "U+2764;",
              "enabled": true
            }
            """;

        EmojiDoc doc = EmojiDoc.parse("heart.json", json);

        assertEquals(1, doc.schemaVersion());
        assertEquals(3L, doc.revision());
        assertEquals("<3", doc.trigger());
        assertEquals("U+2764;", doc.emoji());
        assertTrue(doc.enabled());
    }

    @Test
    void absentEnabledFieldDefaultsToEnabled() {
        String json = """
            {
              "schemaVersion": 1,
              "revision": 3,
              "trigger": "<3",
              "emoji": "U+2764;"
            }
            """;

        EmojiDoc doc = EmojiDoc.parse("heart.json", json);

        assertTrue(doc.enabled());
    }

    @Test
    void explicitFalseEnabledStaysDisabled() {
        String json = """
            {
              "schemaVersion": 1,
              "revision": 3,
              "trigger": "<3",
              "emoji": "U+2764;",
              "enabled": false
            }
            """;

        EmojiDoc doc = EmojiDoc.parse("heart.json", json);

        assertFalse(doc.enabled());
    }

    @Test
    void showAcceptsBooleansAndExpressionsAndDefaultsToTrue() {
        String base = "{\"schemaVersion\":1,\"revision\":1,\"trigger\":\"<3\",\"emoji\":\"visible\"";
        assertEquals(ShowCondition.ALWAYS, EmojiDoc.parse("test.json", base + "}").show());
        assertEquals(ShowCondition.ALWAYS, EmojiDoc.parse("test.json", base + ",\"show\":true}").show());
        assertEquals(ShowCondition.NEVER, EmojiDoc.parse("test.json", base + ",\"show\":false}").show());
        EmojiDoc conditional = EmojiDoc.parse("test.json", base + ",\"show\":\"world.time < 12000\"}");
        assertTrue(conditional.show().isDynamic());
        assertEquals("world.time < 12000", conditional.show().expression());
        assertEquals(conditional, EmojiDoc.parse("test.json", BukkitJson.GSON.toJson(conditional)));
        for (String invalid : List.of("[]", "{}", "42", "\"world.time >\"")) {
            assertThrows(IllegalArgumentException.class,
                () -> EmojiDoc.parse("test.json", base + ",\"show\":" + invalid + "}"));
        }
    }

    @Test
    void gsonRoundTripPreservesAllFields() {
        EmojiDoc original = new EmojiDoc(1, 5L, "", "U+2708;", false, ShowCondition.ALWAYS);

        EmojiDoc decoded = EmojiDoc.parse("airplane.json", BukkitJson.GSON.toJson(original));

        assertEquals(original, decoded);
    }

    @Test
    void legacyShapeWithoutEnvelopeIsRejected() {
        String legacy = "{\"trigger\":\"<3\",\"emoji\":\"U+2764;\",\"enabled\":true}";

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> EmojiDoc.parse("heart.json", legacy));

        assertTrue(failure.getMessage().contains("schemaVersion"));
    }

    @Test
    void wrongSchemaVersionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EmojiDoc(2, 1L, "", "U+2764;", true, ShowCondition.ALWAYS));
    }

    @Test
    void revisionBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> new EmojiDoc(1, 0L, "", "U+2764;", true, ShowCondition.ALWAYS));
        assertThrows(IllegalArgumentException.class,
            () -> new EmojiDoc(1, DocumentEnvelope.MAX_SAFE_REVISION + 1L, "", "U+2764;", true, ShowCondition.ALWAYS));
    }

    @Test
    void nullTriggerNormalizesToEmpty() {
        assertEquals("", new EmojiDoc(1, 1L, null, "U+2764;", true, ShowCondition.ALWAYS).trigger());
    }

    @Test
    void missingEmojiValueIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EmojiDoc(1, 1L, "", null, true, ShowCondition.ALWAYS));
        assertThrows(IllegalArgumentException.class, () -> new EmojiDoc(1, 1L, "", "  ", true, ShowCondition.ALWAYS));
    }
}
