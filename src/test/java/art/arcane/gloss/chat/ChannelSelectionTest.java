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

    @Test
    void selectedVariantOverridesWholeBlocksAndKeepsUnspecifiedBaseValues() {
        ChannelDoc document = ChannelDoc.parse("global.json", """
            {"schemaVersion":2,"revision":1,"channel":{"name":"global"},
             "format":"base-format","card":["base-card"],
             "filters":[{"match":"bad","replace":"base"}],
             "variants":[{"id":"special","priority":1,"when":"viewer.name == 'Alex'",
               "card":[],"mentions":{"pattern":"!{name}","sound":"custom.cue"},
               "items":{"token":"[hand]"},"links":{"enabled":false},
               "filters":[{"match":"bad","replace":"variant"}],"throttle":{"minIntervalTicks":42}}]}
            """);
        ChannelRuntime runtime = ChannelRuntime.of("global", document);
        ChannelRuntime selected = runtime.selected(new TestScope(Map.of("viewer.name", "Alex"), Set.of()),
            BoundedConditionErrorCallback.silent());
        assertEquals("base-format", selected.doc().format());
        assertEquals(List.of(), selected.doc().card());
        assertEquals("custom.cue", selected.doc().mentions().sound());
        assertEquals(false, selected.doc().links().enabled());
        assertEquals(42, selected.doc().throttle().minIntervalTicks());
        assertEquals("variant", ChatFilters.apply(selected, "bad"));
        Matcher scanner = selected.bodyScanner().matcher("!Alex [hand]");
        assertTrue(scanner.find());
        assertEquals("Alex", scanner.group(ChannelRuntime.MENTION_NAME_GROUP));
        assertTrue(scanner.find());
        assertEquals("[hand]", scanner.group(ChannelRuntime.ITEM_GROUP));
        ChannelRuntime other = runtime.selected(new TestScope(Map.of("viewer.name", "Robin"), Set.of()),
            BoundedConditionErrorCallback.silent());
        assertEquals(List.of("base-card"), other.doc().card());
        assertEquals("base", ChatFilters.apply(other, "bad"));
    }

    private static ChannelRuntime runtime(ChannelDoc.Variant... variants) {
        ChannelDoc doc = new ChannelDoc(ChannelDoc.CURRENT_SCHEMA_VERSION, 1L, null,
            new ChannelDoc.Channel("global", List.of("g"), null, null, null, null, null, null),
            "base-format", List.of(), null, null, null,
            List.of(new ChannelDoc.Filter("(?i)\\bbadword\\b", "***")), null, null, List.of(variants));
        return ChannelRuntime.of("global", doc);
    }

    private static ChannelDoc.Variant variant(String id, int priority, String when, String format) {
        return new ChannelDoc.Variant(id, priority, when, format, null, null, null, null, null, null, null);
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
