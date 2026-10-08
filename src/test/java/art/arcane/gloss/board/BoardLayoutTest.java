package art.arcane.gloss.board;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.expr.ExprScope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardLayoutTest {
    private static final BoundedConditionErrorCallback ERRORS = BoundedConditionErrorCallback.bounded(1, error -> {
        throw new AssertionError(error.message());
    });

    @Test
    void conditionalRowsCompactWithoutChangingSurvivingIdentity() {
        BoardDoc document = document("""
            {"lines":[{"id":"staff","text":"Staff","show":"viewer.op"},{"id":"balance","text":"Balance","value":"10"}]}
            """);
        GlossBoardMeta meta = GlossBoardMeta.fromDoc("test", document);
        GlossBoardMeta.RenderPlan plan = meta.renderPlan("base", document.presentation(), 0, 256, UnaryOperator.identity());
        BoardRenderCache.Entry cache = new BoardRenderCache().entry(UUID.randomUUID());
        BoardRenderCache.Frame first = cache.frame(plan, plan.visibleRows(scope(true), ERRORS, 15));
        BoardRenderCache.Frame second = cache.frame(plan, plan.visibleRows(scope(false), ERRORS, 15));
        assertArrayEquals(new int[]{0, 1}, first.rows());
        assertArrayEquals(new int[]{1}, second.rows());
        assertEquals(first.slots()[1], second.slots()[0]);
        assertEquals(BoardLineFormat.FIXED, plan.format(1));
    }

    @Test
    void implicitAndAuthoredRowIdentitiesSurviveFilteringAndContentReload() {
        BoardDoc document = document("""
            {"lines":[{"text":"Staff","show":"viewer.op"},
              {"id":"account","text":"Account"},{"text":"Balance","value":"10"}]}
            """);
        GlossBoardMeta meta = GlossBoardMeta.fromDoc("test", document);
        GlossBoardMeta.RenderPlan initial = meta.renderPlan("base", meta.presentation(), 0, 256, UnaryOperator.identity());
        BoardRenderCache.Entry cache = new BoardRenderCache().entry(UUID.randomUUID());
        BoardRenderCache.Frame all = cache.frame(initial, initial.visibleRows(scope(true), ERRORS, 15));
        BoardRenderCache.Frame filtered = cache.frame(initial, initial.visibleRows(scope(false), ERRORS, 15));
        assertEquals("#0", initial.rowId(0));
        assertEquals("account", initial.rowId(1));
        assertEquals("#2", initial.rowId(2));
        assertSame(initial.rowId(2), initial.rowId(2));
        assertArrayEquals(new int[]{1, 2}, filtered.rows());
        assertEquals(all.slots()[2], filtered.slots()[1]);
        assertEquals(BoardLineFormat.FIXED, initial.format(2));

        meta.setLine(2, new BoardLine("Updated balance", "20", BoardLineFormat.NUMBER));
        GlossBoardMeta.RenderPlan reloaded = meta.renderPlan("base", meta.presentation(), 0, 256, UnaryOperator.identity());
        BoardRenderCache.Frame refreshed = cache.frame(reloaded, reloaded.visibleRows(scope(false), ERRORS, 15));
        assertEquals("#2", reloaded.rowId(2));
        assertEquals("20", reloaded.staticValue(2));
        assertEquals(BoardLineFormat.NUMBER, reloaded.format(2));
        assertArrayEquals(filtered.slots(), refreshed.slots());
        assertArrayEquals(new int[]{0, 1, 2}, reloaded.visibleRows(scope(true), ERRORS, 15));
    }

    @Test
    void sectionsExpandInPlaceAndCombineReferenceConditions() {
        BoardDoc document = document("""
            {"lines":["Header",{"section":"account","show":"viewer.op"}],
             "layout":{"sections":{"account":[{"id":"name","text":"Name"},{"id":"balance","text":"Balance","show":"viewer.op"}]}}}
            """);
        GlossBoardMeta meta = GlossBoardMeta.fromDoc("test", document);
        GlossBoardMeta.RenderPlan plan = meta.renderPlan("base", document.presentation(), 0, 256, UnaryOperator.identity());
        assertEquals(3, plan.lineCount());
        assertArrayEquals(new int[]{0}, plan.visibleRows(scope(false), ERRORS, 15));
        assertArrayEquals(new int[]{0, 1, 2}, plan.visibleRows(scope(true), ERRORS, 15));
        assertEquals("balance", plan.rowId(2));
    }

    @Test
    void pagesRotateWithAuthoredDurationsAndConditionalFallback() {
        BoardDoc document = document("""
            {"title":"Base","lines":["Fallback"],"layout":{"pages":[
              {"id":"one","title":"First","lines":["A"],"show":"viewer.op","durationTicks":2},
              {"id":"two","lines":["B"],"show":"viewer.op","durationTicks":3}]}}
            """);
        BoardLayout layout = document.presentation().layout();
        assertEquals("one", layout.page(scope(true), ERRORS, 0).id());
        assertEquals("one", layout.page(scope(true), ERRORS, 1).id());
        assertEquals("two", layout.page(scope(true), ERRORS, 2).id());
        assertEquals("two", layout.page(scope(true), ERRORS, 4).id());
        assertEquals("one", layout.page(scope(true), ERRORS, 5).id());
        assertNull(layout.page(scope(false), ERRORS, 3));
        GlossBoardMeta meta = GlossBoardMeta.fromDoc("test", document);
        GlossBoardMeta.RenderPlan first = meta.renderPlan("base", document.presentation(), 0, 256, UnaryOperator.identity(), layout.pages().getFirst());
        GlossBoardMeta.RenderPlan second = meta.renderPlan("base", document.presentation(), 0, 256, UnaryOperator.identity(), layout.pages().getLast());
        assertEquals("First", first.rawTitle());
        assertEquals("Base", second.rawTitle());
        assertEquals("B", second.rawLine(0));
        assertSame(first, meta.renderPlan("base", document.presentation(), 0, 256, UnaryOperator.identity(), layout.pages().getFirst()));
    }

    @Test
    void literalRowsAndPagesStayScopeFreeWhileRotatingAndFiltering() {
        BoardDoc document = document("""
            {"lines":[{"text":"Fallback","show":"(true)"}],"layout":{"pages":[
              {"id":"one","durationTicks":2,"show":true,"lines":["First",{"text":"Hidden","show":false}]},
              {"id":"two","durationTicks":3,"show":"(true)","lines":["Second"]}]}}
            """);
        GlossBoardMeta meta = GlossBoardMeta.fromDoc("test", document);
        GlossBoardMeta.ActiveProfile profile = meta.activeProfile(scope(false), ERRORS);
        BoardLayout layout = profile.presentation().layout();
        assertFalse(profile.renderRequiresScope());
        assertTrue(meta.usesFastRefreshText());
        assertEquals("one", layout.page(scope(false), ERRORS, 0).id());
        assertEquals("two", layout.page(scope(false), ERRORS, 2).id());
        GlossBoardMeta.RenderPlan first = meta.renderPlan(profile.id(), profile.presentation(), 0, 256,
            UnaryOperator.identity(), layout.pages().getFirst());
        assertArrayEquals(new int[]{0}, first.visibleRows(scope(false), ERRORS, 15));
        assertEquals("First", first.staticLine(0));
    }

    @Test
    void inactivePageDynamicRowsStillRequireAnUpfrontScope() {
        BoardDoc document = document("""
            {"lines":["Fallback"],"layout":{"pages":[{"id":"inactive","show":false,
              "lines":[{"text":"Staff","show":"viewer.op"}]}]}}
            """);
        GlossBoardMeta meta = GlossBoardMeta.fromDoc("test", document);
        GlossBoardMeta.ActiveProfile profile = meta.activeProfile(scope(false), ERRORS);
        assertTrue(profile.renderRequiresScope());
        assertNull(profile.presentation().layout().page(scope(true), ERRORS, 0));
    }

    @Test
    void rowConditionEditsRecompileScopeRequirementsAndObserveCurrentVisibility() {
        GlossBoardMeta meta = GlossBoardMeta.fromDoc("test", document("{\"lines\":[\"Staff\"]}"));
        assertFalse(meta.activeProfile(scope(false), ERRORS).renderRequiresScope());
        meta.setLine(0, new BoardLine("Staff", null, null, null, ShowCondition.of("viewer.op"), null));
        GlossBoardMeta.ActiveProfile dynamic = meta.activeProfile(scope(false), ERRORS);
        assertTrue(dynamic.renderRequiresScope());
        GlossBoardMeta.RenderPlan plan = meta.renderPlan(dynamic.id(), dynamic.presentation(), 0, 256, UnaryOperator.identity());
        assertArrayEquals(new int[0], plan.visibleRows(scope(false), ERRORS, 15));
        assertArrayEquals(new int[]{0}, plan.visibleRows(scope(true), ERRORS, 15));
        assertArrayEquals(new int[0], plan.visibleRows(scope(false), ERRORS, 15));
        meta.setLine(0, BoardLine.of("Staff"));
        assertFalse(meta.activeProfile(scope(false), ERRORS).renderRequiresScope());
    }

    @Test
    void variantSelectionKeepsCurrentScopeAndRecompilesPresentationRequirements() {
        GlossBoardMeta meta = GlossBoardMeta.fromDoc("test", document("{\"lines\":[\"Base\"]}"));
        BoardDoc.Presentation literal = document("{\"lines\":[\"Staff\"]}").presentation();
        meta.setVariants(List.of(new BoardDoc.Variant("staff", 1, "viewer.op", literal)));
        GlossBoardMeta.ActiveProfile selected = meta.activeProfile(scope(true), ERRORS);
        assertEquals("staff", selected.id());
        assertFalse(selected.renderRequiresScope());
        assertEquals("base", meta.activeProfile(scope(false), ERRORS).id());

        BoardDoc.Presentation dynamic = document("""
            {"lines":["Staff"],"layout":{"pages":[{"id":"staff-page","show":"viewer.op","lines":["Page"]}]}}
            """).presentation();
        meta.setVariants(List.of(new BoardDoc.Variant("staff", 1, "viewer.op", dynamic)));
        GlossBoardMeta.ActiveProfile changed = meta.activeProfile(scope(true), ERRORS);
        assertTrue(changed.renderRequiresScope());
        assertEquals("staff-page", changed.presentation().layout().page(scope(true), ERRORS, 0).id());
        assertNull(changed.presentation().layout().page(scope(false), ERRORS, 0));
        assertEquals("base", meta.activeProfile(scope(false), ERRORS).id());
        meta.setVariants(List.of(new BoardDoc.Variant("staff", 1, "viewer.op", literal)));
        assertFalse(meta.activeProfile(scope(true), ERRORS).renderRequiresScope());
    }

    @Test
    void filtersBeforeApplyingVanillaRowLimit() {
        List<BoardLine> rows = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            rows.add(new BoardLine("row-" + index, null, null, null,
                ShowCondition.of(index < 5 ? "false" : "true"), null));
        }
        BoardDoc.Presentation presentation = new BoardDoc.Presentation("Title", rows, true);
        GlossBoardMeta.RenderPlan plan = new GlossBoardMeta("test").renderPlan("base", presentation, 0, 256, UnaryOperator.identity());
        int[] visible = plan.visibleRows(scope(false), ERRORS, 15);
        assertEquals(15, visible.length);
        assertEquals(5, visible[0]);
        assertEquals(19, visible[14]);
        assertThrows(IllegalArgumentException.class, () -> new BoardDoc.Presentation("Title", rows, true,
            new BoardLayout(null, null, "error", null)));
    }

    @Test
    void rejectsUnknownRecursiveDuplicateAndConflictingReferences() {
        for (String json : List.of(
            "{\"lines\":[{\"section\":\"missing\"}]}",
            "{\"lines\":[{\"id\":\"x\",\"text\":\"A\"},{\"id\":\"x\",\"text\":\"B\"}]}",
            "{\"lines\":[{\"section\":\"x\",\"text\":\"A\"}]}",
            "{\"layout\":{\"sections\":{\"x\":[{\"section\":\"y\"}],\"y\":[{\"section\":\"x\"}]}}}")) {
            assertThrows(IllegalArgumentException.class, () -> document(json));
        }
    }

    @Test
    void titleTextAndValueCadencesAreIndependent() {
        BoardDoc document = document("""
            {"title":"%title%","lines":[{"text":"%text%","value":"%value%"}],
             "layout":{"refresh":{"titleTicks":20,"textTicks":10,"valueTicks":2}}}
            """);
        GlossBoardMeta.RenderPlan plan = new GlossBoardMeta("test").renderPlan("base", document.presentation(), 0, 256, UnaryOperator.identity());
        BoardRenderCache.Entry cache = new BoardRenderCache().entry(UUID.randomUUID());
        List<String> calls = new ArrayList<>();
        UnaryOperator<String> render = raw -> { calls.add(raw); return raw; };
        cache.title(plan, 0, 1_000_000_000L, 0, render);
        cache.lines(plan, 0, 1_000_000_000L, 0, render);
        cache.value(plan, 0, 0, 1_000_000_000L, 0, render);
        calls.clear();
        cache.title(plan, 100_000_000L, 1_000_000_000L, 0, render);
        cache.lines(plan, 100_000_000L, 1_000_000_000L, 0, render);
        cache.value(plan, 0, 100_000_000L, 1_000_000_000L, 0, render);
        assertEquals(List.of("%value%"), calls);
        calls.clear();
        cache.title(plan, 500_000_000L, 1_000_000_000L, 0, render);
        cache.lines(plan, 500_000_000L, 1_000_000_000L, 0, render);
        cache.value(plan, 0, 500_000_000L, 1_000_000_000L, 0, render);
        assertEquals(List.of("%text%", "%value%"), calls);
    }

    @Test
    void commandEditsKeepAuthoredIdentityAndLayout() {
        BoardDoc document = document("""
            {"lines":[{"id":"balance","text":"Old","value":"10","show":"viewer.op"}],
             "layout":{"refresh":{"valueTicks":2},"pages":[{"id":"page","lines":["A"]}]}}
            """);
        GlossBoardMeta meta = GlossBoardMeta.fromDoc("test", document);
        meta.setLine(0, "New");
        BoardDoc roundtrip = BoardDoc.parse("test", DocumentParsers.GSON.toJson(meta.toDoc(2)));
        assertEquals("balance", roundtrip.presentation().lines().getFirst().id());
        assertEquals("viewer.op", roundtrip.presentation().lines().getFirst().show().expression());
        assertEquals("New", roundtrip.presentation().lines().getFirst().text());
        assertEquals(2, roundtrip.presentation().layout().refresh().valueTicks());
        assertEquals(1, roundtrip.presentation().layout().pages().size());
        assertTrue(meta.usesFastRefreshText());
        assertTrue(meta.usesFastRefresh(false));
        assertFalse(roundtrip.presentation().lines().getFirst().isPlainText());
    }

    private static BoardDoc document(String presentation) {
        return BoardDoc.parse("test", "{\"schemaVersion\":2,\"revision\":1,\"presentation\":" + presentation + "}");
    }

    private static ExprScope scope(boolean op) {
        return new TestScope(Map.of("viewer.op", op));
    }

    private record TestScope(Map<String, Object> values) implements ExprScope {
        @Override
        public Object variable(String name) {
            return values.get(name);
        }

        @Override
        public Object call(String name, List<Object> arguments) {
            return null;
        }
    }
}
