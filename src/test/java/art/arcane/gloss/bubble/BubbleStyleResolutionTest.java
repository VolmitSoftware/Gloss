package art.arcane.gloss.bubble;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BubbleStyleResolutionTest {
    @Test
    void permittedExplicitChoiceWinsOverConditionalStyles() {
        Map<String, BubbleStyles.CompiledStyle> styles = compile(Map.of(
            "default", style(null),
            "fancy", style(null),
            "automatic", style(select(100, "true"))));

        String chosen = resolve("fancy", Set.of("gloss.bubbles.style.fancy"), styles, TestScope.EMPTY);

        assertEquals("fancy", chosen);
    }

    @Test
    void deniedOrUnknownExplicitChoiceFallsThrough() {
        Map<String, BubbleStyles.CompiledStyle> styles = compile(Map.of(
            "default", style(null),
            "fancy", style(null)));

        assertEquals("default", resolve("fancy", Set.of(), styles, TestScope.EMPTY));
        assertEquals("default", resolve("missing", Set.of("gloss.bubbles.style.missing"), styles,
            TestScope.EMPTY));
    }

    @Test
    void highestPriorityConditionMatchWins() {
        Map<String, BubbleStyles.CompiledStyle> styles = compile(Map.of(
            "default", style(null),
            "world", style(select(10, "subject.world == 'world_nether'")),
            "critical", style(select(100, "subject.health < 5")),
            "staff", style(select(50, "hasPermission('subject', 'gloss.staff')"))));
        TestScope scope = new TestScope(Map.of("subject.world", "world_nether", "subject.health", 4.0D),
            Set.of("gloss.staff"));

        assertEquals("critical", resolve(null, Set.of(), styles, scope));
    }

    @Test
    void equalPriorityUsesIdAndFalseConditionsUseDefault() {
        Map<String, BubbleStyles.CompiledStyle> styles = compile(Map.of(
            "default", style(null),
            "zeta", style(select(20, "subject.op")),
            "alpha", style(select(20, "subject.op"))));

        assertEquals("alpha", resolve(null, Set.of(), styles,
            new TestScope(Map.of("subject.op", true), Set.of())));
        assertEquals("default", resolve(null, Set.of(), styles,
            new TestScope(Map.of("subject.op", false), Set.of())));
    }

    @Test
    void styleWithoutSelectionIsNeverAutoMatchedAndMissingDefaultYieldsNull() {
        assertNull(resolve(null, Set.of(), compile(Map.of("fancy", style(null))), TestScope.EMPTY));
        assertNull(resolve(null, Set.of(), Map.of(), TestScope.EMPTY));
    }

    private static Map<String, BubbleStyles.CompiledStyle> compile(Map<String, BubbleStyleDoc> styles) {
        return BubbleStyles.compile(styles);
    }

    private static String resolve(String chosen, Set<String> permissions,
                                  Map<String, BubbleStyles.CompiledStyle> styles, ExprScope scope) {
        return BubbleStyles.resolveStyleId(chosen, permissions::contains, styles, scope,
            BoundedConditionErrorCallback.silent());
    }

    private static BubbleStyleDoc style(BubbleStyleDoc.Select select) {
        return new BubbleStyleDoc(4, 1L, "&7", null, 32, 5000L, true, true,
            BubbleStyleDoc.DEFAULTS.motion(), BubbleStyleDoc.DEFAULTS.shimmer(), select, List.of());
    }

    private static BubbleStyleDoc.Select select(int priority, String when) {
        return new BubbleStyleDoc.Select(priority, when);
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
