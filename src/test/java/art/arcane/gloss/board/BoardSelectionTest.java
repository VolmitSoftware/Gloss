package art.arcane.gloss.board;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BoardSelectionTest {
    @Test
    void highestPriorityMatchingBoardWinsRegardlessOfInputOrder() {
        List<GlossBoardMeta> boards = List.of(
            board("ordinary", 10, "true"),
            board("arena", 80, "viewer.world == 'arena'"),
            board("staff", 50, "hasPermission('viewer', 'gloss.staff')")
        );
        TestScope scope = new TestScope(Map.of("viewer.world", "arena"), Set.of("gloss.staff"));

        assertEquals("arena", BoardService.selectBoardId(boards, scope));
    }

    @Test
    void equalPriorityUsesIdAsStableTieBreaker() {
        List<GlossBoardMeta> boards = List.of(
            board("zeta", 40, "true"),
            board("alpha", 40, "true")
        );

        assertEquals("alpha", BoardService.selectBoardId(boards, TestScope.EMPTY));
    }

    @Test
    void falseAndFailingSelectionsAreSkipped() {
        List<GlossBoardMeta> boards = List.of(
            board("false", 100, "false"),
            board("missing", 90, "unknown.value == 1"),
            board("match", 1, "viewer.health < 5")
        );

        assertEquals("match", BoardService.selectBoardId(boards,
            new TestScope(Map.of("viewer.health", 4.0D), Set.of())));
        assertNull(BoardService.selectBoardId(List.of(board("none", 1, "false")), TestScope.EMPTY));
        assertNull(BoardService.selectBoardId(List.of(), TestScope.EMPTY));
    }

    @Test
    void highestPriorityMatchingVariantWinsAndProfilesAreComplete() {
        GlossBoardMeta meta = board("main", 1, "true");
        meta.setTitle("Base");
        meta.addLine("base-line");
        meta.setVariants(List.of(
            variant("staff", 20, "hasPermission('viewer', 'gloss.staff')", "Staff"),
            variant("critical", 100, "viewer.health < 5", "Critical"),
            variant("arena", 50, "viewer.world == 'arena'", "Arena")
        ));
        TestScope scope = new TestScope(Map.of("viewer.health", 4.0D, "viewer.world", "arena"),
            Set.of("gloss.staff"));

        GlossBoardMeta.ActiveProfile profile = meta.activeProfile(scope, BoundedConditionErrorCallback.silent());

        assertEquals("critical", profile.id());
        assertEquals("Critical", profile.presentation().title());
        assertEquals(List.of("Critical-line"), profile.presentation().lines());
    }

    @Test
    void variantTieUsesIdAndNoMatchUsesBasePresentation() {
        GlossBoardMeta meta = board("main", 1, "true");
        meta.setTitle("Base");
        meta.setVariants(List.of(
            variant("zeta", 20, "viewer.level > 10", "Zeta"),
            variant("alpha", 20, "viewer.level > 10", "Alpha")
        ));

        GlossBoardMeta.ActiveProfile selected = meta.activeProfile(
            new TestScope(Map.of("viewer.level", 12.0D), Set.of()), BoundedConditionErrorCallback.silent());
        GlossBoardMeta.ActiveProfile base = meta.activeProfile(
            new TestScope(Map.of("viewer.level", 2.0D), Set.of()), BoundedConditionErrorCallback.silent());

        assertEquals("alpha", selected.id());
        assertEquals("Alpha", selected.presentation().title());
        assertEquals("base", base.id());
        assertEquals("Base", base.presentation().title());
    }

    private static GlossBoardMeta board(String id, int priority, String when) {
        GlossBoardMeta meta = new GlossBoardMeta(id);
        meta.setSelection(priority, when);
        return meta;
    }

    private static BoardDoc.Variant variant(String id, int priority, String when, String title) {
        return new BoardDoc.Variant(id, priority, when,
            new BoardDoc.Presentation(title, List.of(title + "-line"), false));
    }

    private record TestScope(Map<String, Object> variables, Set<String> permissions) implements ExprScope {
        private static final TestScope EMPTY = new TestScope(Map.of(), Set.of());

        @Override
        public Object variable(String dottedName) {
            return variables.get(dottedName);
        }

        @Override
        public Object call(String name, List<Object> args) {
            if (name.equals("hasPermission")) {
                return permissions.contains(args.get(1));
            }
            return ExprFunctions.call(name, args);
        }
    }
}
