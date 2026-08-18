package art.arcane.gloss.board;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BoardSelectionTest {
    private static GlossBoardMeta board(String id, boolean primary, String permission) {
        GlossBoardMeta meta = new GlossBoardMeta(id);
        meta.setPrimary(primary);
        meta.setPermission(permission);
        return meta;
    }

    @Test
    void groupDefaultBoardWinsOverEverything() {
        List<GlossBoardMeta> boards = List.of(
            board("gated", false, "vip"),
            board("group-board", false, "default"),
            board("main", true, "default")
        );

        String chosen = BoardService.selectBoardId("group-board", boards, node -> true);

        assertEquals("group-board", chosen);
    }

    @Test
    void missingGroupDefaultFallsThroughToPermissionGated() {
        List<GlossBoardMeta> boards = List.of(
            board("gated", false, "vip"),
            board("main", true, "default")
        );

        String chosen = BoardService.selectBoardId("gone", boards, Set.of("gloss.board.vip")::contains);

        assertEquals("gated", chosen);
    }

    @Test
    void nullGroupDefaultUsesFirstGrantedGatedBoard() {
        List<GlossBoardMeta> boards = List.of(
            board("alpha", false, "alpha"),
            board("beta", false, "beta"),
            board("main", true, "default")
        );

        String chosen = BoardService.selectBoardId(null, boards, Set.of("gloss.board.beta")::contains);

        assertEquals("beta", chosen);
    }

    @Test
    void blankGroupDefaultBehavesLikeNull() {
        List<GlossBoardMeta> boards = List.of(
            board("gated", false, "vip"),
            board("main", true, "default")
        );

        String chosen = BoardService.selectBoardId("   ", boards, node -> true);

        assertEquals("gated", chosen);
    }

    @Test
    void firstGrantedGatedBoardWinsInOrder() {
        List<GlossBoardMeta> boards = List.of(
            board("first", false, "one"),
            board("second", false, "two")
        );

        String chosen = BoardService.selectBoardId(null, boards, node -> true);

        assertEquals("first", chosen);
    }

    @Test
    void ungatedBoardsAreNotChosenByPermissionPass() {
        List<GlossBoardMeta> boards = List.of(
            board("open", false, "default"),
            board("main", true, "default")
        );

        String chosen = BoardService.selectBoardId(null, boards, node -> true);

        assertEquals("main", chosen);
    }

    @Test
    void deniedGatedBoardsFallThroughToPrimary() {
        List<GlossBoardMeta> boards = List.of(
            board("gated", false, "vip"),
            board("main", true, "default")
        );

        String chosen = BoardService.selectBoardId(null, boards, node -> false);

        assertEquals("main", chosen);
    }

    @Test
    void firstPrimaryBoardWinsWhenNothingElseMatches() {
        List<GlossBoardMeta> boards = List.of(
            board("open", false, "default"),
            board("primary-a", true, "default"),
            board("primary-b", true, "default")
        );

        String chosen = BoardService.selectBoardId(null, boards, node -> false);

        assertEquals("primary-a", chosen);
    }

    @Test
    void noMatchYieldsNull() {
        List<GlossBoardMeta> boards = List.of(
            board("gated", false, "vip"),
            board("open", false, "default")
        );

        String chosen = BoardService.selectBoardId(null, boards, node -> false);

        assertNull(chosen);
    }

    @Test
    void emptyBoardListYieldsNull() {
        assertNull(BoardService.selectBoardId("anything", List.of(), node -> true));
    }
}
