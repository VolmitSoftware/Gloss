package art.arcane.gloss.nameplate;

import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

class NameplateSelectionTest {
    @Test
    void theHighestPriorityMatchingDocumentWins() {
        List<NameplateRuntime> runtimes = List.of(
            runtime("ordinary", 10, "true"),
            runtime("staff", 80, "hasPermission('subject', 'server.staff')"));

        Assertions.assertEquals("staff", NameplateRuntime.select(runtimes,
            new TestScope(Map.of(), Set.of("server.staff"))).id());
        Assertions.assertEquals("ordinary", NameplateRuntime.select(runtimes,
            new TestScope(Map.of(), Set.of())).id());
    }

    @Test
    void equalPriorityBreaksTheTieOnTheLowestId() {
        List<NameplateRuntime> runtimes = List.of(runtime("zeta", 40, "true"), runtime("alpha", 40, "true"));

        Assertions.assertEquals("alpha",
            NameplateRuntime.select(runtimes, new TestScope(Map.of(), Set.of())).id());
    }

    @Test
    void aDocumentWhoseShowIsFalseIsNeverSelected() {
        NameplateDoc hidden = NameplateDoc.parse("hidden.json", """
            { "schemaVersion": 1, "revision": 1, "show": "false",
              "select": { "priority": 100, "when": "true" } }
            """);
        List<NameplateRuntime> runtimes = List.of(
            new NameplateRuntime("hidden", hidden), runtime("ordinary", 1, "true"));

        Assertions.assertEquals("ordinary",
            NameplateRuntime.select(runtimes, new TestScope(Map.of(), Set.of())).id());
    }

    @Test
    void nothingMatchingSelectsNothing() {
        Assertions.assertNull(NameplateRuntime.select(List.of(runtime("only", 1, "false")),
            new TestScope(Map.of(), Set.of())));
    }

    @Test
    void aVariantOverridesThePresentationWithoutMerging() {
        NameplateDoc doc = NameplateDoc.parse("default.json", """
            { "schemaVersion": 1, "revision": 1,
              "select": { "priority": 0, "when": "true" },
              "presentation": { "lines": [ { "text": "base" } ], "offset": 0.1 },
              "variants": [ { "id": "staff", "priority": 5, "when": "hasPermission('subject', 'server.staff')",
                              "presentation": { "lines": [ { "text": "staff" } ] } } ] }
            """);
        NameplateRuntime runtime = new NameplateRuntime("default", doc);

        NameplateDoc.Presentation staff = runtime.presentation(new TestScope(Map.of(), Set.of("server.staff")));
        NameplateDoc.Presentation base = runtime.presentation(new TestScope(Map.of(), Set.of()));

        Assertions.assertEquals("staff", staff.lines().getFirst().text());
        Assertions.assertEquals(NameplateDoc.DEFAULT_OFFSET, staff.offset());
        Assertions.assertEquals("base", base.lines().getFirst().text());
        Assertions.assertEquals(0.1D, base.offset());
    }

    @Test
    void linesAreFilteredByTheirOwnShowCondition() {
        NameplateDoc doc = NameplateDoc.parse("default.json", """
            { "schemaVersion": 1, "revision": 1,
              "presentation": { "lines": [
                { "text": "always" },
                { "text": "hurt", "show": "subject.health < subject.maxHealth" } ] } }
            """);
        NameplateRuntime runtime = new NameplateRuntime("default", doc);
        TestScope healthy = new TestScope(Map.of("subject.health", 20.0D, "subject.maxHealth", 20.0D), Set.of());
        TestScope hurt = new TestScope(Map.of("subject.health", 5.0D, "subject.maxHealth", 20.0D), Set.of());

        Assertions.assertEquals(List.of("always"), runtime.visibleLines(healthy, healthy));
        Assertions.assertEquals(List.of("always", "hurt"), runtime.visibleLines(hurt, hurt));
    }

    @Test
    void aDocumentThatReadsTheViewerIsViewerDependent() {
        NameplateDoc shared = NameplateDoc.parse("shared.json", """
            { "schemaVersion": 1, "revision": 1,
              "presentation": { "lines": [ { "text": "&f{{ subject.name }}" } ] } }
            """);
        NameplateDoc personal = NameplateDoc.parse("personal.json", """
            { "schemaVersion": 1, "revision": 1,
              "presentation": { "lines": [ { "text": "&f{{ viewer.name }}" } ] } }
            """);

        Assertions.assertFalse(new NameplateRuntime("shared", shared).viewerDependent());
        Assertions.assertTrue(new NameplateRuntime("personal", personal).viewerDependent());
    }

    @Test
    void aRelationColorIsPickedByItsCondition() {
        NameplateDoc doc = NameplateDoc.parse("default.json", """
            { "schemaVersion": 1, "revision": 1,
              "presentation": { "relations": [
                { "when": "hasPermission('subject', 'server.staff')", "color": "&c" },
                { "when": "true", "color": "&7" } ] } }
            """);
        NameplateRuntime runtime = new NameplateRuntime("default", doc);

        Assertions.assertEquals("&c", runtime.relationColor(
            runtime.presentation(new TestScope(Map.of(), Set.of())),
            new TestScope(Map.of(), Set.of("server.staff"))));
        Assertions.assertEquals("&7", runtime.relationColor(
            runtime.presentation(new TestScope(Map.of(), Set.of())),
            new TestScope(Map.of(), Set.of())));
    }

    private static NameplateRuntime runtime(String id, int priority, String when) {
        return new NameplateRuntime(id, NameplateDoc.parse(id + ".json", """
            { "schemaVersion": 1, "revision": 1, "select": { "priority": %d, "when": "%s" } }
            """.formatted(priority, when)));
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
