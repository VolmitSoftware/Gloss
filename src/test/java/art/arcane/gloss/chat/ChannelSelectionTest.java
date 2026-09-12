package art.arcane.gloss.chat;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelSelectionTest {
    @Test
    void theHighestPriorityMatchingVariantWins() {
        ChannelRuntime runtime = runtime(
            variant("staff", 20, "hasPermission('sender', 'gloss.staff')", "staff-format"),
            variant("critical", 100, "sender.health < 5", "critical-format"),
            variant("arena", 50, "sender.world == 'arena'", "arena-format"));
        TestScope scope = new TestScope(Map.of("sender.health", 4.0D, "sender.world", "arena"),
            Set.of("gloss.staff"));

        assertEquals("critical-format", runtime.format(scope, BoundedConditionErrorCallback.silent()));
        assertEquals("critical", runtime.activeVariant(scope, BoundedConditionErrorCallback.silent()).id());
    }

    @Test
    void anEqualPriorityTieUsesTheIdAndNoMatchUsesTheBaseFormat() {
        ChannelRuntime runtime = runtime(
            variant("zeta", 20, "sender.level > 10", "zeta-format"),
            variant("alpha", 20, "sender.level > 10", "alpha-format"));

        assertEquals("alpha-format", runtime.format(new TestScope(Map.of("sender.level", 12.0D), Set.of()),
            BoundedConditionErrorCallback.silent()));
        assertEquals("base-format", runtime.format(new TestScope(Map.of("sender.level", 2.0D), Set.of()),
            BoundedConditionErrorCallback.silent()));
        assertNull(runtime.activeVariant(new TestScope(Map.of("sender.level", 2.0D), Set.of()),
            BoundedConditionErrorCallback.silent()));
    }

    @Test
    void aFailingVariantConditionIsSkipped() {
        ChannelRuntime runtime = runtime(
            variant("broken", 90, "unknown.value == 1", "broken-format"),
            variant("ok", 10, "true", "ok-format"));

        assertEquals("ok-format", runtime.format(TestScope.EMPTY, BoundedConditionErrorCallback.silent()));
    }

    @Test
    void filtersCompileOnceAtLoad() {
        ChannelRuntime runtime = runtime();

        assertEquals(1, runtime.filters().size());
        assertTrue(runtime.filters().getFirst().pattern().matcher("say BADWORD now").find());
    }

    @Test
    void theBodyScannerFindsLinksMentionsAndTheItemTokenInOnePass() {
        ChannelRuntime runtime = runtime();
        Matcher matcher = runtime.bodyScanner().matcher("hi @Steve see https://volmit.com and [item]");

        assertTrue(matcher.find());
        assertEquals("Steve", matcher.group(ChannelRuntime.MENTION_NAME_GROUP));
        assertTrue(matcher.find());
        assertEquals("https://volmit.com", matcher.group(ChannelRuntime.LINK_GROUP));
        assertTrue(matcher.find());
        assertEquals("[item]", matcher.group(ChannelRuntime.ITEM_GROUP));
    }

    private static ChannelRuntime runtime(ChannelDoc.Variant... variants) {
        ChannelDoc doc = new ChannelDoc(ChannelDoc.CURRENT_SCHEMA_VERSION, 1L, null,
            new ChannelDoc.Channel("global", List.of("g"), null, null, null, null, null, null),
            "base-format", List.of(), null, null, null,
            List.of(new ChannelDoc.Filter("(?i)\\bbadword\\b", "***")), null, List.of(variants));
        return ChannelRuntime.of("global", doc);
    }

    private static ChannelDoc.Variant variant(String id, int priority, String when, String format) {
        return new ChannelDoc.Variant(id, priority, when, format);
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
