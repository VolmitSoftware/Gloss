package art.arcane.gloss.tab;

import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TablistDocTest {
    @Test
    void parseReadsIndependentConditionalSurfaces() {
        String json = """
            {
              "schemaVersion": 2,
              "revision": 2,
              "headerFooter": {
                "enabled": true,
                "presentation": {"header": "&d&lGloss", "footer": "&7VolmitSoftware.com"},
                "variants": [{
                  "id": "arena", "priority": 50, "when": "viewer.world == 'arena'",
                  "presentation": {"header": "&cArena", "footer": "&7Fight"}
                }]
              },
              "listNames": {
                "enabled": true,
                "presentation": {"format": "$player"},
                "variants": [{
                  "id": "operator", "priority": 100, "when": "subject.op",
                  "presentation": {"format": "&6$player"}
                }]
              }
            }
            """;

        TablistDoc doc = TablistDoc.parse("tablist.json", json);

        assertEquals(2, doc.schemaVersion());
        assertEquals(2L, doc.revision());
        assertTrue(doc.headerFooter().enabled());
        assertEquals("&d&lGloss", doc.headerFooter().presentation().header());
        assertEquals("arena", doc.headerFooter().variants().getFirst().id());
        assertTrue(doc.listNames().enabled());
        assertEquals("&6$player", doc.listNames().variants().getFirst().presentation().format());
    }

    @Test
    void gsonRoundTripPreservesAllFields() {
        TablistDoc original = document(
            new TablistDoc.HeaderFooterPresentation("&aTop", "&7Bottom"),
            List.of(new TablistDoc.HeaderFooterVariant("vip", 20, "viewer.level > 10",
                new TablistDoc.HeaderFooterPresentation("VIP", "Footer"))),
            new TablistDoc.ListNamePresentation("$player"),
            List.of(new TablistDoc.ListNameVariant("staff", 30, "subject.op",
                new TablistDoc.ListNamePresentation("&c$player"))));

        TablistDoc decoded = TablistDoc.parse("tablist.json", BukkitJson.GSON.toJson(original));

        assertEquals(original, decoded);
    }

    @Test
    void retiredV1ShapeAndInvalidRevisionsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> TablistDoc.parse("tablist.json", "{\"schemaVersion\":1,\"revision\":1}"));
        assertThrows(IllegalArgumentException.class,
            () -> new TablistDoc(2, 0L, ShowCondition.ALWAYS, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new TablistDoc(2, DocumentEnvelope.MAX_SAFE_REVISION + 1L, ShowCondition.ALWAYS, null, null));
    }

    @Test
    void missingSurfacesUseOperationalDefaults() {
        TablistDoc doc = TablistDoc.parse("tablist.json", "{\"schemaVersion\":2,\"revision\":1}");

        assertEquals(TablistDoc.HeaderFooter.DEFAULTS, doc.headerFooter());
        assertEquals(TablistDoc.ListNames.DEFAULTS, doc.listNames());
        assertFalse(doc.listNames().presentation().format().isBlank());
    }

    @Test
    void conditionsAndVariantIdsAreValidatedAtLoad() {
        TablistDoc.HeaderFooterPresentation presentation = TablistDoc.HeaderFooterPresentation.EMPTY;
        TablistDoc.HeaderFooterVariant first = new TablistDoc.HeaderFooterVariant("alert", 1,
            "viewer.health < 5", presentation);
        TablistDoc.HeaderFooterVariant duplicate = new TablistDoc.HeaderFooterVariant("alert", 2,
            "true", presentation);

        assertThrows(IllegalArgumentException.class,
            () -> new TablistDoc.HeaderFooter(true, ShowCondition.ALWAYS, presentation, List.of(first, duplicate)));
        assertThrows(IllegalArgumentException.class,
            () -> new TablistDoc.ListNameVariant("bad id", 1, "true",
                new TablistDoc.ListNamePresentation("$player")));
        assertThrows(IllegalArgumentException.class,
            () -> new TablistDoc.HeaderFooterVariant("broken", 1, "viewer.health <", presentation));
    }

    @Test
    void runtimeUsesPriorityThenIdAndFallsBackToCompleteBaseProfiles() {
        TablistDoc doc = document(
            new TablistDoc.HeaderFooterPresentation("Base", "Base footer"),
            List.of(
                new TablistDoc.HeaderFooterVariant("staff", 50, "viewer.op",
                    new TablistDoc.HeaderFooterPresentation("Staff", "Staff footer")),
                new TablistDoc.HeaderFooterVariant("critical", 100, "viewer.health < 5",
                    new TablistDoc.HeaderFooterPresentation("Critical", "Critical footer"))),
            new TablistDoc.ListNamePresentation("$player"),
            List.of(
                new TablistDoc.ListNameVariant("zeta", 40, "subject.op",
                    new TablistDoc.ListNamePresentation("Zeta $player")),
                new TablistDoc.ListNameVariant("alpha", 40, "subject.op",
                    new TablistDoc.ListNamePresentation("Alpha $player"))));
        TablistRuntime runtime = TablistRuntime.compile(doc);
        TestScope matching = new TestScope(Map.of(
            "viewer.op", true,
            "viewer.health", 4.0D,
            "subject.op", true));
        TestScope ordinary = new TestScope(Map.of(
            "viewer.op", false,
            "viewer.health", 20.0D,
            "subject.op", false));

        assertEquals("critical", runtime.headerFooter(matching,
            BoundedConditionErrorCallback.silent()).id());
        assertEquals("alpha", runtime.listName(matching,
            BoundedConditionErrorCallback.silent()).id());
        assertEquals("base", runtime.headerFooter(ordinary,
            BoundedConditionErrorCallback.silent()).id());
        assertEquals("$player", runtime.listName(ordinary,
            BoundedConditionErrorCallback.silent()).presentation().format());
    }

    @Test
    void missingAndNullShowRemainVisible() {
        TablistDoc missing = TablistDoc.parse("tablist.json", "{\"schemaVersion\":2,\"revision\":1}");
        TablistDoc explicitNull = TablistDoc.parse("tablist.json", """
            {"schemaVersion":2,"revision":1,"show":null,
             "headerFooter":{"enabled":true,"show":null},
             "listNames":{"enabled":true,"show":null}}
            """);

        for (TablistDoc doc : List.of(missing, explicitNull)) {
            assertTrue(doc.show().isAlwaysVisible());
            assertTrue(doc.headerFooter().show().isAlwaysVisible());
            assertTrue(doc.listNames().show().isAlwaysVisible());
        }
    }

    @Test
    void topLevelShowGatesBothSurfacesAndTheirVariants() {
        TablistDoc doc = TablistDoc.parse("tablist.json", """
            {"schemaVersion":2,"revision":1,"show":false,
             "headerFooter":{"enabled":true,"variants":[
               {"id":"always","when":"true","presentation":{"header":"Visible"}}]},
             "listNames":{"enabled":true,"variants":[
               {"id":"always","when":"true","presentation":{"format":"Admin $player"}}]}}
            """);
        TablistRuntime runtime = TablistRuntime.compile(doc);
        TestScope scope = new TestScope(Map.of());

        assertNull(runtime.headerFooter(scope, BoundedConditionErrorCallback.silent()));
        assertNull(runtime.listName(scope, BoundedConditionErrorCallback.silent()));
        assertFalse(runtime.headerFooterVisible(scope, BoundedConditionErrorCallback.silent()));
        assertTrue(doc.headerFooter().enabled());
        assertTrue(doc.listNames().enabled());
    }

    @Test
    void showReevaluatesWorldAndTimeWithoutLosingVariantSelection() {
        TablistDoc doc = TablistDoc.parse("tablist.json", """
            {"schemaVersion":2,"revision":1,"show":"{{ player.world != 'hidden' }}",
             "headerFooter":{"enabled":true,"show":"world.time < 12000",
               "variants":[{"id":"staff","when":"viewer.op",
                 "presentation":{"header":"Staff"}}]},
             "listNames":{"enabled":true,"show":"subject.op",
               "presentation":{"format":"Staff $player"}}}
            """);
        TablistRuntime runtime = TablistRuntime.compile(doc);
        TestScope day = new TestScope(Map.of("player.world", "world", "world.time", 1000.0D,
            "viewer.op", true, "subject.op", false));
        TestScope night = new TestScope(Map.of("player.world", "world", "world.time", 14000.0D,
            "viewer.op", true, "subject.op", true));
        TestScope hidden = new TestScope(Map.of("player.world", "hidden", "world.time", 1000.0D,
            "viewer.op", true, "subject.op", true));
        BoundedConditionErrorCallback errors = BoundedConditionErrorCallback.silent();

        assertEquals("staff", runtime.headerFooter(day, errors).id());
        assertNull(runtime.listName(day, errors));
        assertNull(runtime.headerFooter(night, errors));
        assertEquals("Staff $player", runtime.listName(night, errors).presentation().format());
        assertNull(runtime.headerFooter(hidden, errors));
        assertNull(runtime.listName(hidden, errors));
        assertEquals("staff", runtime.headerFooter(day, errors).id());
        assertEquals(doc, TablistDoc.parse("tablist.json", BukkitJson.GSON.toJson(doc)));
    }

    @Test
    void enabledFalseStillDisablesVisibleSurfaces() {
        TablistDoc doc = TablistDoc.parse("tablist.json", """
            {"schemaVersion":2,"revision":1,"show":true,
             "headerFooter":{"enabled":false,"show":true},
             "listNames":{"enabled":false,"show":true}}
            """);
        TablistRuntime runtime = TablistRuntime.compile(doc);
        TestScope scope = new TestScope(Map.of());

        assertNull(runtime.headerFooter(scope, BoundedConditionErrorCallback.silent()));
        assertNull(runtime.listName(scope, BoundedConditionErrorCallback.silent()));
    }

    @Test
    void tokenSubstitutionIsSinglePass() {
        assertEquals("&cAlex [staff $group]",
            TablistService.substituteTokens("&c$player [$group]", "Alex", "staff $group"));
    }

    private static TablistDoc document(TablistDoc.HeaderFooterPresentation header,
                                       List<TablistDoc.HeaderFooterVariant> headerVariants,
                                       TablistDoc.ListNamePresentation names,
                                       List<TablistDoc.ListNameVariant> nameVariants) {
        return new TablistDoc(2, 1L, ShowCondition.ALWAYS,
            new TablistDoc.HeaderFooter(true, ShowCondition.ALWAYS, header, headerVariants),
            new TablistDoc.ListNames(true, ShowCondition.ALWAYS, names, nameVariants));
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
