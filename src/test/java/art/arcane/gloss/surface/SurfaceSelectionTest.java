package art.arcane.gloss.surface;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceSelectionTest {
    @Test
    void aDocumentIsSelectedOnlyWhenShowAndSelectBothMatch() {
        SurfaceRuntime runtime = actionBar("arena", 10, "viewer.world == 'arena'", "true");

        assertTrue(runtime.selected(new TestScope(Map.of("viewer.world", "arena")), silent()));
        assertFalse(runtime.selected(new TestScope(Map.of("viewer.world", "lobby")), silent()));
    }

    @Test
    void aShowConditionThatIsFalseKeepsTheDocumentUnselected() {
        SurfaceRuntime runtime = SurfaceRuntime.compile("hidden", new SurfaceDoc(
            SurfaceDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION, SurfaceKind.ACTIONBAR,
            ShowCondition.NEVER, new SurfaceDoc.Selection(10, "true"), text("x"), List.of()));

        assertFalse(runtime.selected(TestScope.EMPTY, silent()));
    }

    @Test
    void aThrowingConditionCountsAsUnselected() {
        SurfaceRuntime runtime = actionBar("broken", 10, "true", "missing.value == 1");

        assertFalse(runtime.selected(TestScope.EMPTY, silent()));
    }

    @Test
    void theHighestPriorityCandidateWinsAndTiesBreakByLowestId() {
        SurfaceRuntime low = actionBar("zeta", 10, "true", "true");
        SurfaceRuntime high = actionBar("beta", 80, "true", "true");
        SurfaceRuntime tie = actionBar("alpha", 80, "true", "true");

        assertEquals("beta", SurfaceRuntime.pick(List.of(low, high), TestScope.EMPTY, silent())
            .map(SurfaceRuntime::id).orElse(null));
        assertEquals("alpha", SurfaceRuntime.pick(List.of(low, high, tie), TestScope.EMPTY, silent())
            .map(SurfaceRuntime::id).orElse(null));
    }

    @Test
    void pickReturnsEmptyWhenNothingIsSelected() {
        Optional<SurfaceRuntime> picked = SurfaceRuntime.pick(
            List.of(actionBar("a", 10, "true", "false")), TestScope.EMPTY, silent());

        assertTrue(picked.isEmpty());
    }

    @Test
    void theHighestPriorityMatchingVariantWinsAndTiesBreakByLowestId() {
        SurfaceRuntime runtime = SurfaceRuntime.compile("arena", new SurfaceDoc(
            SurfaceDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION, SurfaceKind.ACTIONBAR,
            ShowCondition.ALWAYS, new SurfaceDoc.Selection(10, "true"), text("base"),
            List.of(new SurfaceDoc.Variant("zeta", 20, "viewer.level > 10", text("zeta")),
                new SurfaceDoc.Variant("alpha", 20, "viewer.level > 10", text("alpha")),
                new SurfaceDoc.Variant("low", 5, "true", text("low")))));

        assertEquals("alpha", runtime.profile(new TestScope(Map.of("viewer.level", 12.0D)), silent()).id());
        assertEquals("alpha", runtime.profile(new TestScope(Map.of("viewer.level", 12.0D)), silent())
            .presentation().text());
        assertEquals("low", runtime.profile(new TestScope(Map.of("viewer.level", 2.0D)), silent()).id());
    }

    @Test
    void theBasePresentationIsUsedWhenNoVariantMatches() {
        SurfaceRuntime runtime = SurfaceRuntime.compile("arena", new SurfaceDoc(
            SurfaceDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION, SurfaceKind.ACTIONBAR,
            ShowCondition.ALWAYS, new SurfaceDoc.Selection(10, "true"), text("base"),
            List.of(new SurfaceDoc.Variant("high", 20, "viewer.level > 10", text("high")))));

        SurfaceRuntime.SurfaceProfile profile = runtime.profile(new TestScope(Map.of("viewer.level", 1.0D)), silent());

        assertEquals("base", profile.id());
        assertEquals("base", profile.presentation().text());
    }

    @Test
    void aBossBarProfileCarriesItsCompiledProgressExpression() {
        SurfaceRuntime runtime = SurfaceRuntime.compile("arena", new SurfaceDoc(
            SurfaceDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION, SurfaceKind.BOSSBAR,
            ShowCondition.ALWAYS, new SurfaceDoc.Selection(10, "true"),
            new SurfaceDoc.Presentation(null, null, "Arena", null, "viewer.level / 100", "red", "solid",
                "status", 40, null, null, null, null, null),
            List.of()));

        SurfaceRuntime.SurfaceProfile profile = runtime.profile(new TestScope(Map.of("viewer.level", 25.0D)), silent());

        assertEquals(0.25D, profile.progress(new TestScope(Map.of("viewer.level", 25.0D))), 1.0E-9D);
        assertEquals(1.0D, profile.progress(new TestScope(Map.of("viewer.level", 900.0D))), 1.0E-9D);
        assertEquals(0.0D, profile.progress(new TestScope(Map.of("viewer.level", -5.0D))), 1.0E-9D);
    }

    @Test
    void aProgressExpressionThatFailsAtRuntimeReadsAsZero() {
        SurfaceRuntime runtime = SurfaceRuntime.compile("arena", new SurfaceDoc(
            SurfaceDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION, SurfaceKind.BOSSBAR,
            ShowCondition.ALWAYS, new SurfaceDoc.Selection(10, "true"),
            new SurfaceDoc.Presentation(null, null, "Arena", null, "missing.value", "red", "solid",
                "status", 40, null, null, null, null, null),
            List.of()));

        assertEquals(0.0D, runtime.profile(TestScope.EMPTY, silent()).progress(TestScope.EMPTY), 1.0E-9D);
    }

    private static SurfaceRuntime actionBar(String id, int priority, String show, String when) {
        return SurfaceRuntime.compile(id, new SurfaceDoc(SurfaceDoc.CURRENT_SCHEMA_VERSION,
            DocumentEnvelope.INITIAL_REVISION, SurfaceKind.ACTIONBAR, ShowCondition.of(show),
            new SurfaceDoc.Selection(priority, when), text(id), List.of()));
    }

    private static SurfaceDoc.Presentation text(String text) {
        return new SurfaceDoc.Presentation(text, null, null, null, null, null, null, null, null, null,
            null, null, null, null);
    }

    private static BoundedConditionErrorCallback silent() {
        return BoundedConditionErrorCallback.silent();
    }

    private record TestScope(Map<String, Object> variables) implements ExprScope {
        private static final TestScope EMPTY = new TestScope(Map.of());

        @Override
        public Object variable(String dottedName) {
            return variables.get(dottedName);
        }

        @Override
        public Object call(String name, List<Object> args) {
            return ExprFunctions.call(name, args);
        }
    }
}
