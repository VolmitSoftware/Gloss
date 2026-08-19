package art.arcane.gloss.board;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardDocTest {
    @Test
    void parseReadsTheV2Shape() {
        String json = """
            {
              "schemaVersion": 1,
              "revision": 4,
              "title": "&6Board",
              "lines": ["a", "b"],
              "primary": true,
              "permission": "staff",
              "groups": ["vip"]
            }
            """;

        BoardDoc doc = BoardDoc.parse("legacy.json", json);

        assertEquals(1, doc.schemaVersion());
        assertEquals(4L, doc.revision());
        assertEquals("&6Board", doc.title());
        assertEquals(List.of("a", "b"), doc.lines());
        assertTrue(doc.primary());
        assertEquals("staff", doc.permission());
        assertEquals(List.of("vip"), doc.groups());
    }

    @Test
    void gsonRoundTripPreservesAllFields() {
        BoardDoc original = new BoardDoc(1, 12L, "&d&lArena", List.of("one", "two"), false, "vip", List.of("mods"));

        BoardDoc decoded = BoardDoc.parse("arena.json", BukkitJson.GSON.toJson(original));

        assertEquals(original, decoded);
    }

    @Test
    void legacyShapeWithoutEnvelopeIsRejected() {
        String legacy = "{\"title\":\"&6Board\",\"content\":[\"a\",\"b\"],\"primary\":false,\"permission\":\"staff\"}";

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> BoardDoc.parse("legacy.json", legacy));

        assertTrue(failure.getMessage().contains("schemaVersion"));
    }

    @Test
    void wrongSchemaVersionIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new BoardDoc(2, 1L, "t", List.of(), false, "default", List.of()));
    }

    @Test
    void revisionBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class,
            () -> new BoardDoc(1, 0L, "t", List.of(), false, "default", List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new BoardDoc(1, DocumentEnvelope.MAX_SAFE_REVISION + 1L, "t", List.of(), false, "default", List.of()));
    }

    @Test
    void missingCollectionsDefaultToEmpty() {
        BoardDoc doc = BoardDoc.parse("bare.json", "{\"schemaVersion\":1,\"revision\":1}");

        assertEquals("", doc.title());
        assertEquals(List.of(), doc.lines());
        assertFalse(doc.primary());
        assertEquals(GlossBoardMeta.UNRESTRICTED_PERMISSION, doc.permission());
        assertEquals(List.of(), doc.groups());
    }

    @Test
    void groupsAreNormalizedLowercaseTrimmedAndDeduplicated() {
        BoardDoc doc = new BoardDoc(1, 1L, "t", List.of(), false, "default",
            Arrays.asList(" VIP ", "vip", "", null, "Mods"));

        assertEquals(List.of("vip", "mods"), doc.groups());
    }

    @Test
    void permissionNormalizesLikeTheMeta() {
        assertEquals("default", new BoardDoc(1, 1L, "t", List.of(), false, "  ", List.of()).permission());
        assertEquals("vip", new BoardDoc(1, 1L, "t", List.of(), false, " VIP ", List.of()).permission());
    }

    @Test
    void withRevisionOnlyChangesTheRevision() {
        BoardDoc doc = new BoardDoc(1, 1L, "t", List.of("x"), true, "vip", List.of("mods"));

        BoardDoc bumped = doc.withRevision(2L);

        assertEquals(2L, bumped.revision());
        assertEquals(doc.title(), bumped.title());
        assertEquals(doc.lines(), bumped.lines());
        assertEquals(doc.primary(), bumped.primary());
        assertEquals(doc.permission(), bumped.permission());
        assertEquals(doc.groups(), bumped.groups());
    }
}
