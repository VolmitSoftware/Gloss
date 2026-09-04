package art.arcane.gloss.board;

import art.arcane.gloss.condition.ShowCondition;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlossBoardMetaDocTest {
    @Test
    void docRoundTripPreservesTheConditionalContract() {
        GlossBoardMeta meta = new GlossBoardMeta("arena");
        meta.setTitle("&d&lArena");
        meta.addLine("&7Line one");
        meta.setHideNumbers(true);
        meta.setShow(ShowCondition.of("world.time > 12000"));
        meta.setSelection(30, "viewer.world == 'arena'");
        meta.setVariants(List.of(new BoardDoc.Variant("critical", 100, "viewer.health < 5",
            new BoardDoc.Presentation("&cDanger", List.of("heal"), false))));

        BoardDoc doc = meta.toDoc(7L);
        GlossBoardMeta restored = GlossBoardMeta.fromDoc("arena", doc);

        assertEquals("arena", restored.id());
        assertEquals("&d&lArena", restored.title());
        assertEquals(List.of("&7Line one"), restored.lines());
        assertTrue(restored.hideNumbers());
        assertEquals(new BoardDoc.Selection(30, "viewer.world == 'arena'"), restored.selection());
        assertEquals(meta.variants(), restored.variants());
        assertEquals(meta.show(), restored.show());
        assertEquals(7L, restored.revision());
    }

    @Test
    void fromDocWithBlankTitleFallsBackToTheId() {
        BoardDoc doc = new BoardDoc(2, 1L, ShowCondition.ALWAYS, BoardDoc.Selection.NEVER,
            new BoardDoc.Presentation("", List.of(), false), List.of());

        GlossBoardMeta meta = GlossBoardMeta.fromDoc("bare", doc);

        assertEquals("bare", meta.title());
        assertEquals(BoardDoc.Selection.NEVER, meta.selection());
    }

    @Test
    void selectionSetterWritesTheRawCondition() {
        GlossBoardMeta meta = new GlossBoardMeta("gated");

        meta.setSelection(42, "hasPermission('viewer', 'gloss.board.vip')");

        assertEquals(42, meta.selection().priority());
        assertEquals("hasPermission('viewer', 'gloss.board.vip')", meta.selection().when());
    }

    @Test
    void lineMutatorsEditContentInPlace() {
        GlossBoardMeta meta = new GlossBoardMeta("lines");
        meta.addLine("one");
        meta.addLine("two");
        meta.addLine("three");

        meta.setLine(1, "TWO");
        meta.removeLine(0);

        assertEquals(List.of("TWO", "three"), meta.lines());
    }

    @Test
    void linesReturnsImmutableCopy() {
        GlossBoardMeta meta = new GlossBoardMeta("frozen");
        meta.addLine("one");

        List<String> snapshot = meta.lines();

        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("two"));
        assertEquals(List.of("one"), meta.lines());
    }

    @Test
    void newMetaStartsBeforeTheFirstDocRevision() {
        GlossBoardMeta meta = new GlossBoardMeta("fresh");

        assertEquals(0L, meta.revision());
        assertEquals(1L, meta.nextRevision());
        assertEquals(2L, meta.nextRevision());
        assertEquals(2L, meta.revision());
    }
}
