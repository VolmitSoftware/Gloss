package art.arcane.gloss.nametag;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NametagSelectionTest {
    @Test
    void theHighestPriorityCandidateWinsAndTiesBreakByLowestId() {
        NametagRuntime low = runtime("zeta", 10, "true");
        NametagRuntime high = runtime("beta", 80, "true");
        NametagRuntime tie = runtime("alpha", 80, "true");

        assertEquals("beta", NametagRuntime.pick(List.of(low, high), scope(), silent())
            .map(NametagRuntime::id).orElse(null));
        assertEquals("alpha", NametagRuntime.pick(List.of(low, high, tie), scope(), silent())
            .map(NametagRuntime::id).orElse(null));
    }

    @Test
    void aThrowingConditionCountsAsUnselected() {
        assertFalse(runtime("broken", 10, "missing.value == 1").selected(scope(), silent()));
        assertTrue(NametagRuntime.pick(List.of(runtime("broken", 10, "missing.value == 1")), scope(), silent())
            .isEmpty());
    }

    @Test
    void theHighestPriorityMatchingVariantWins() {
        NametagRuntime runtime = NametagRuntime.compile("tags", new NametagDoc(
            NametagDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION, ShowCondition.ALWAYS,
            new NametagDoc.Selection(0, "true"), presentation("&7base"),
            List.of(new NametagDoc.Variant("staff", 10, "subject.op", presentation("&cstaff")),
                new NametagDoc.Variant("combat", 20, "subject.health < 5", presentation("&4combat")))));

        assertEquals("combat", runtime.profile(
            new TestScope(Map.of("subject.op", Boolean.TRUE, "subject.health", 1.0D)), silent()).id());
        assertEquals("staff", runtime.profile(
            new TestScope(Map.of("subject.op", Boolean.TRUE, "subject.health", 20.0D)), silent()).id());
        assertEquals("base", runtime.profile(
            new TestScope(Map.of("subject.op", Boolean.FALSE, "subject.health", 20.0D)), silent()).id());
    }

    @Test
    void aDocumentReadingOnlyTheSubjectResolvesOncePerSubject() {
        assertFalse(runtime("plain", 0, "subject.op").viewerDependent());
        assertTrue(runtime("perViewer", 0, "viewer.op").viewerDependent());
    }

    @Test
    void aPrefixReadingTheViewerAlsoForcesThePerViewerPass() {
        NametagRuntime runtime = NametagRuntime.compile("tags", new NametagDoc(
            NametagDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION, ShowCondition.ALWAYS,
            new NametagDoc.Selection(0, "true"), presentation("&7{{ viewer.name }} "), List.of()));

        assertTrue(runtime.viewerDependent());
    }

    @Test
    void aVariantReadingTheViewerAlsoForcesThePerViewerPass() {
        NametagRuntime runtime = NametagRuntime.compile("tags", new NametagDoc(
            NametagDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION, ShowCondition.ALWAYS,
            new NametagDoc.Selection(0, "true"), presentation("&7base"),
            List.of(new NametagDoc.Variant("near", 10, "viewer.health < 5", presentation("&4near")))));

        assertTrue(runtime.viewerDependent());
    }

    private static NametagRuntime runtime(String id, int priority, String when) {
        return NametagRuntime.compile(id, new NametagDoc(NametagDoc.CURRENT_SCHEMA_VERSION,
            DocumentEnvelope.INITIAL_REVISION, ShowCondition.ALWAYS,
            new NametagDoc.Selection(priority, when), presentation("&7" + id), List.of()));
    }

    private static NametagDoc.Presentation presentation(String prefix) {
        return new NametagDoc.Presentation(prefix, "", "white", "always", "always");
    }

    private static ExprScope scope() {
        return new TestScope(Map.of("subject.op", Boolean.TRUE, "viewer.op", Boolean.TRUE));
    }

    private static BoundedConditionErrorCallback silent() {
        return BoundedConditionErrorCallback.silent();
    }

    private record TestScope(Map<String, Object> variables) implements ExprScope {
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
