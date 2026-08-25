package art.arcane.gloss.tab;

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
            () -> new TablistDoc(2, 0L, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new TablistDoc(2, DocumentEnvelope.MAX_SAFE_REVISION + 1L, null, null));
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
            () -> new TablistDoc.HeaderFooter(true, presentation, List.of(first, duplicate)));
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
    void tokenSubstitutionIsSinglePass() {
        assertEquals("&cAlex [staff $group]",
            TablistService.substituteTokens("&c$player [$group]", "Alex", "staff $group"));
    }

    private static TablistDoc document(TablistDoc.HeaderFooterPresentation header,
                                       List<TablistDoc.HeaderFooterVariant> headerVariants,
                                       TablistDoc.ListNamePresentation names,
                                       List<TablistDoc.ListNameVariant> nameVariants) {
        return new TablistDoc(2, 1L,
            new TablistDoc.HeaderFooter(true, header, headerVariants),
            new TablistDoc.ListNames(true, names, nameVariants));
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
