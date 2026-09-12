package art.arcane.gloss.marker;

import art.arcane.gloss.api.MarkerAnchor;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

class MarkerSelectionTest {
    @Test
    void keepsTheNearestMarkersUpToTheCap() {
        List<MarkerCandidate> candidates = List.of(
            candidate("far", 200), candidate("near", 10), candidate("mid", 60));

        List<MarkerCandidate> selected = MarkerSelection.select(candidates, 2);

        Assertions.assertEquals(List.of("near", "mid"), selected.stream().map(MarkerCandidate::id).toList());
    }

    @Test
    void dropsMarkersBeyondTheirOwnMaxDistance() {
        MarkerSpec close = MarkerSpec.at("close", "world", 0, 0, 0);
        MarkerSpec limited = new MarkerSpec("limited", MarkerAnchor.position("world", 0, 0, 0), "", null,
            0xFFFFFF, null, 0, 32, null, null, null, ShowCondition.ALWAYS, 0, false);

        List<MarkerCandidate> selected = MarkerSelection.select(List.of(
            new MarkerCandidate(MarkerRuntime.of(close), "world", 0, 0, 0, 40),
            new MarkerCandidate(MarkerRuntime.of(limited), "world", 0, 0, 0, 40)), 8);

        Assertions.assertEquals(List.of("close"), selected.stream().map(MarkerCandidate::id).toList());
    }

    @Test
    void dropsMarkersInsideTheirHideWithinRadius() {
        MarkerSpec hidden = new MarkerSpec("hidden", MarkerAnchor.position("world", 0, 0, 0), "", null,
            0xFFFFFF, null, 8, 256, null, null, null, ShowCondition.ALWAYS, 0, false);

        List<MarkerCandidate> selected = MarkerSelection.select(List.of(
            new MarkerCandidate(MarkerRuntime.of(hidden), "world", 0, 0, 0, 4)), 8);

        Assertions.assertTrue(selected.isEmpty());
    }

    @Test
    void aMarkerAtExactlyItsMaxDistanceIsStillShown() {
        MarkerSpec limited = new MarkerSpec("limited", MarkerAnchor.position("world", 0, 0, 0), "", null,
            0xFFFFFF, null, 0, 32, null, null, null, ShowCondition.ALWAYS, 0, false);

        Assertions.assertEquals(1, MarkerSelection.select(List.of(
            new MarkerCandidate(MarkerRuntime.of(limited), "world", 0, 0, 0, 32)), 8).size());
    }

    @Test
    void anAudienceConditionGatesTheMarker() {
        MarkerSpec staffOnly = new MarkerSpec("staff", MarkerAnchor.position("world", 0, 0, 0), "", null,
            0xFFFFFF, null, 0, 256, null, null, null,
            ShowCondition.of("hasPermission('viewer', 'quests.mill')"), 0, false);
        MarkerRuntime runtime = MarkerRuntime.of(staffOnly);

        Assertions.assertTrue(runtime.visible(new TestScope(Map.of(), Set.of("quests.mill"))));
        Assertions.assertFalse(runtime.visible(new TestScope(Map.of(), Set.of())));
    }

    @Test
    void theDocumentShowConditionGatesTheMarkerToo() {
        MarkerRuntime runtime = MarkerRuntime.of(MarkerSpec.at("mill", "world", 0, 0, 0),
            ShowCondition.of("viewer.world == 'world'"));

        Assertions.assertTrue(runtime.visible(new TestScope(Map.of("viewer.world", "world"), Set.of())));
        Assertions.assertFalse(runtime.visible(new TestScope(Map.of("viewer.world", "nether"), Set.of())));
    }

    @Test
    void distanceScaleReadsTheMarkerNamespace() {
        MarkerSpec scaled = new MarkerSpec("scaled", MarkerAnchor.position("world", 0, 0, 0), "", null,
            0xFFFFFF, "clamp(marker.distance / 24, 1, 5)", 0, 256, null, null, null,
            ShowCondition.ALWAYS, 0, false);
        MarkerRuntime runtime = MarkerRuntime.of(scaled);
        MarkerNamespace namespace = new MarkerNamespace();
        MarkerNamespace.push(new MarkerContext("scaled", 0, 0, 0, 96));
        try {
            ExprScope scope = new NamespaceScope(namespace);
            Assertions.assertEquals(4.0D, runtime.scale(scope), 1.0E-6D);
        } finally {
            MarkerNamespace.pop();
        }
    }

    @Test
    void aMissingDistanceScaleIsOne() {
        Assertions.assertEquals(1.0D,
            MarkerRuntime.of(MarkerSpec.at("plain", "world", 0, 0, 0)).scale(new TestScope(Map.of(), Set.of())),
            1.0E-6D);
    }

    private static MarkerCandidate candidate(String id, double distance) {
        return new MarkerCandidate(MarkerRuntime.of(MarkerSpec.at(id, "world", 0, 0, 0)),
            "world", 0, 0, 0, distance);
    }

    private record NamespaceScope(MarkerNamespace namespace) implements ExprScope {
        @Override
        public Object variable(String dottedName) {
            int dot = dottedName.indexOf('.');
            if (dot <= 0 || !dottedName.substring(0, dot).equals(MarkerNamespace.PREFIX)) {
                return null;
            }
            return namespace.resolve(dottedName.substring(dot + 1), null);
        }

        @Override
        public Object call(String name, List<Object> args) {
            return ExprFunctions.call(name, args);
        }
    }

    private record TestScope(Map<String, Object> variables, Set<String> permissions) implements ExprScope {
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
