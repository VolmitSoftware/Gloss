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

    private static GlossBoardMeta board(String id, boolean primary, String permission, List<String> groups) {
        GlossBoardMeta meta = board(id, primary, permission);
        meta.setGroups(groups);
        return meta;
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
    void grantedGatedBoardWinsOverPrimary() {
        List<GlossBoardMeta> boards = List.of(
            board("gated", false, "vip"),
            board("main", true, "default")
        );

        String chosen = BoardService.selectBoardId(null, boards, Set.of("gloss.board.vip")::contains);

        assertEquals("gated", chosen);
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
        assertNull(BoardService.selectBoardId("anygroup", List.of(), node -> true));
    }

    @Test
    void groupMatchedBoardWinsOverPermissionAndPrimary() {
        List<GlossBoardMeta> boards = List.of(
            board("gated", false, "vip"),
            board("staff-board", false, "default", List.of("staff")),
            board("main", true, "default")
        );

        String chosen = BoardService.selectBoardId("staff", boards, node -> true);

        assertEquals("staff-board", chosen);
    }

    @Test
    void unmatchedPrimaryGroupFallsThrough() {
        List<GlossBoardMeta> boards = List.of(
            board("staff-board", false, "default", List.of("staff")),
            board("main", true, "default")
        );

        String chosen = BoardService.selectBoardId("builders", boards, node -> false);

        assertEquals("main", chosen);
    }

    @Test
    void nullOrBlankPrimaryGroupSkipsGroupMatching() {
        List<GlossBoardMeta> boards = List.of(
            board("staff-board", false, "default", List.of("staff")),
            board("main", true, "default")
        );

        assertEquals("main", BoardService.selectBoardId(null, boards, node -> false));
        assertEquals("main", BoardService.selectBoardId("  ", boards, node -> false));
    }

    @Test
    void aGroupMatchedBoardTheViewerLacksPermissionForIsSkipped() {
        List<GlossBoardMeta> boards = List.of(
            board("staff-board", false, "vip", List.of("staff")),
            board("staff-open", false, "default", List.of("staff")),
            board("main", true, "default")
        );

        assertEquals("staff-open", BoardService.selectBoardId("staff", boards, node -> false));
        assertEquals("staff-board", BoardService.selectBoardId("staff", boards, node -> true));
    }

    @Test
    void aGroupMatchIsStillOnlyReachedThroughItsOwnPermission() {
        List<GlossBoardMeta> boards = List.of(
            board("staff-board", false, "vip", List.of("staff")),
            board("main", true, "default")
        );

        assertEquals("main", BoardService.selectBoardId("staff", boards, node -> false));
        assertEquals("staff-board",
            BoardService.selectBoardId("staff", boards, Set.of("gloss.board.vip")::contains));
    }

    @Test
    void aPrimaryBoardTheViewerLacksPermissionForIsSkipped() {
        List<GlossBoardMeta> boards = List.of(
            board("primary-gated", true, "vip"),
            board("primary-open", true, "default")
        );

        assertEquals("primary-open", BoardService.selectBoardId(null, boards, node -> false));
    }

    @Test
    void aGatedPrimaryBoardIsTheOnlyCandidateAndIsDeniedToAViewerWithoutIt() {
        List<GlossBoardMeta> boards = List.of(board("primary-gated", true, "vip"));

        assertNull(BoardService.selectBoardId(null, boards, node -> false));
        assertEquals("primary-gated",
            BoardService.selectBoardId(null, boards, Set.of("gloss.board.vip")::contains));
    }

    @Test
    void firstGroupMatchedBoardWinsInOrder() {
        List<GlossBoardMeta> boards = List.of(
            board("first", false, "default", List.of("staff")),
            board("second", false, "default", List.of("staff"))
        );

        String chosen = BoardService.selectBoardId("staff", boards, node -> false);

        assertEquals("first", chosen);
    }
}
