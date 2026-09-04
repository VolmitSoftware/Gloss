package art.arcane.gloss.board;

import art.arcane.gloss.condition.ShowCondition;

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
    void parseReadsSelectionPresentationAndCompleteVariants() {
        String json = """
            {
              "schemaVersion": 2,
              "revision": 4,
              "select": {"priority": 40, "when": "viewer.world == 'arena'"},
              "presentation": {"title": "&6Board", "lines": ["a", "b"], "hideNumbers": true},
              "variants": [{
                "id": "critical",
                "priority": 100,
                "when": "viewer.health < 5",
                "presentation": {"title": "&cDanger", "lines": ["heal"], "hideNumbers": false}
              }]
            }
            """;

        BoardDoc doc = BoardDoc.parse("arena.json", json);

        assertEquals(2, doc.schemaVersion());
        assertEquals(4L, doc.revision());
        assertEquals(new BoardDoc.Selection(40, "viewer.world == 'arena'"), doc.select());
        assertEquals(new BoardDoc.Presentation("&6Board", List.of("a", "b"), true), doc.presentation());
        assertEquals("critical", doc.variants().getFirst().id());
        assertEquals("&cDanger", doc.variants().getFirst().presentation().title());
    }

    @Test
    void gsonRoundTripPreservesAllFields() {
        BoardDoc original = new BoardDoc(2, 12L, ShowCondition.of("world.time > 12000"),
            new BoardDoc.Selection(10, "hasPermission('viewer', 'gloss.staff')"),
            new BoardDoc.Presentation("&d&lArena", List.of("one", "two"), true),
            List.of(new BoardDoc.Variant("low-health", 50, "viewer.health < 5",
                new BoardDoc.Presentation("&cWarning", List.of("heal"), false))));

        BoardDoc decoded = BoardDoc.parse("arena.json", BukkitJson.GSON.toJson(original));

        assertEquals(original, decoded);
    }

    @Test
    void retiredV1ShapeIsRejected() {
        String legacy = "{\"schemaVersion\":1,\"revision\":1,\"title\":\"Board\"}";

        assertThrows(IllegalArgumentException.class, () -> BoardDoc.parse("legacy.json", legacy));
    }

    @Test
    void revisionBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class,
            () -> new BoardDoc(2, 0L, ShowCondition.ALWAYS, null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new BoardDoc(2, DocumentEnvelope.MAX_SAFE_REVISION + 1L, ShowCondition.ALWAYS, null, null, null));
    }

    @Test
    void missingFieldsUseNonSelectingEmptyDefaults() {
        BoardDoc doc = BoardDoc.parse("bare.json", "{\"schemaVersion\":2,\"revision\":1}");

        assertEquals(BoardDoc.Selection.NEVER, doc.select());
        assertTrue(doc.show().isAlwaysVisible());
        assertEquals(BoardDoc.Presentation.EMPTY, doc.presentation());
        assertEquals(List.of(), doc.variants());
        assertFalse(doc.presentation().hideNumbers());
    }

    @Test
    void malformedAndNonBooleanConditionsAreRejectedAtLoad() {
        assertThrows(IllegalArgumentException.class,
            () -> new BoardDoc.Selection(1, "viewer.health <"));
        assertThrows(IllegalArgumentException.class,
            () -> new BoardDoc.Selection(1, "5 + 2"));
    }

    @Test
    void variantIdsMustBeUniqueAndSafe() {
        BoardDoc.Presentation presentation = BoardDoc.Presentation.EMPTY;
        BoardDoc.Variant first = new BoardDoc.Variant("alert", 1, "true", presentation);
        BoardDoc.Variant duplicate = new BoardDoc.Variant("alert", 2, "true", presentation);

        assertThrows(IllegalArgumentException.class,
            () -> new BoardDoc(2, 1L, ShowCondition.ALWAYS, BoardDoc.Selection.NEVER, presentation, List.of(first, duplicate)));
        assertThrows(IllegalArgumentException.class,
            () -> new BoardDoc.Variant("bad id", 1, "true", presentation));
    }

    @Test
    void nullPresentationTextAndLinesNormalize() {
        BoardDoc.Presentation presentation = new BoardDoc.Presentation(null, Arrays.asList("one", null), true);

        assertEquals("", presentation.title());
        assertEquals(List.of("one", ""), presentation.lines());
        assertTrue(presentation.hideNumbers());
        assertThrows(UnsupportedOperationException.class, () -> presentation.lines().add("three"));
    }

    @Test
    void withRevisionOnlyChangesTheRevision() {
        BoardDoc doc = new BoardDoc(2, 1L, ShowCondition.ALWAYS, new BoardDoc.Selection(7, "true"),
            new BoardDoc.Presentation("t", List.of("x"), true), List.of());

        BoardDoc bumped = doc.withRevision(2L);

        assertEquals(2L, bumped.revision());
        assertEquals(doc.select(), bumped.select());
        assertEquals(doc.presentation(), bumped.presentation());
        assertEquals(doc.variants(), bumped.variants());
    }
}
