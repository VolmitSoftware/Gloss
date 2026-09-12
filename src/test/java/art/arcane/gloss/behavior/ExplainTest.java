package art.arcane.gloss.behavior;

import art.arcane.gloss.board.BoardDoc;
import art.arcane.gloss.board.GlossBoardMeta;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.tab.TablistDoc;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplainTest {
    private static final BoardDoc.Presentation PRESENTATION =
        BoardDoc.Presentation.ofStrings("&dHub", List.of("&7line"), false);

    private static ExprScope scope(Map<String, Object> values) {
        return new ExprScope() {
            @Override
            public Object variable(String dottedName) {
                return values.get(dottedName);
            }

            @Override
            public Object call(String name, List<Object> args) {
                return ExprFunctions.call(name, args);
            }
        };
    }

    @Test
    void boardExplainReportsEveryConditionAndTheWinner() {
        GlossBoardMeta meta = new GlossBoardMeta("hub");
        meta.setShow(ShowCondition.of("level > 1"));
        meta.setSelection(10, "true");
        meta.setVariants(List.of(
            new BoardDoc.Variant("vip", 10, "level > 9", PRESENTATION),
            new BoardDoc.Variant("regular", 5, "level > 2", PRESENTATION)));

        ExplainReport report = ExplainReports.board(meta, scope(Map.of("level", 4.0D)));

        assertEquals("board", report.kind());
        assertEquals("hub", report.id());
        assertEquals(List.of("show", "select.when", "variants.vip.when", "variants.regular.when"),
            report.lines().stream().map(ExplainReport.Line::path).toList());
        assertEquals(List.of("true", "true", "false", "true"),
            report.lines().stream().map(ExplainReport.Line::value).toList());
        assertEquals(List.of("variants.regular.when"), winners(report));
        assertEquals("level > 9", report.lines().get(2).expression());
    }

    @Test
    void boardExplainNamesTheBaseProfileWhenNoVariantMatches() {
        GlossBoardMeta meta = new GlossBoardMeta("hub");
        meta.setVariants(List.of(new BoardDoc.Variant("vip", 10, "level > 9", PRESENTATION)));

        ExplainReport report = ExplainReports.board(meta, scope(Map.of("level", 1.0D)));

        assertEquals(List.of("base"), winners(report));
    }

    @Test
    void tablistExplainCoversHeaderFooterAndListNameVariants() {
        TablistDoc doc = TablistDoc.parse("tablist.json", """
            {"schemaVersion":2,"revision":1,
             "headerFooter":{"enabled":true,"variants":[
               {"id":"vip","priority":10,"when":"level > 9","presentation":{"header":"a","footer":"b"}},
               {"id":"regular","priority":1,"when":"level > 2","presentation":{"header":"c","footer":"d"}}]},
             "listNames":{"enabled":true,"variants":[
               {"id":"afk","priority":1,"when":"level > 99","presentation":{"format":"$player"}}]}}
            """);

        ExplainReport report = ExplainReports.tablist(doc, scope(Map.of("level", 4.0D)));

        assertEquals("tablist", report.kind());
        assertEquals(List.of("show", "headerFooter.show", "headerFooter.variants.vip.when",
                "headerFooter.variants.regular.when", "listNames.show", "listNames.variants.afk.when",
                "listNames.base"),
            report.lines().stream().map(ExplainReport.Line::path).toList());
        assertEquals(List.of("headerFooter.variants.regular.when", "listNames.base"), winners(report));
    }

    @Test
    void behaviorExplainReportsEachEntryGateAndWhichOnesWouldRun() {
        BehaviorDoc doc = BehaviorDoc.parse("quests.json", """
            {"schemaVersion":1,"revision":1,"on":[
              {"trigger":"join","when":"level > 9","do":[]},
              {"trigger":"join","when":"level > 2","do":[]},
              {"trigger":"interval","everyTicks":20,"do":[]}]}
            """);
        BehaviorRuntime runtime = BehaviorRuntime.compile("quests", doc);

        ExplainReport report = ExplainReports.behavior(runtime, scope(Map.of("level", 4.0D)));

        assertEquals("behavior", report.kind());
        assertEquals("quests", report.id());
        assertEquals(List.of("on[0].when", "on[1].when", "on[2].when"),
            report.lines().stream().map(ExplainReport.Line::path).toList());
        assertEquals(List.of("false", "true", "true"),
            report.lines().stream().map(ExplainReport.Line::value).toList());
        assertEquals(List.of("on[1].when", "on[2].when"), winners(report));
        assertEquals("true", report.lines().get(2).expression());
    }

    @Test
    void theRegistryHandsBackWhatEachLaneRegisteredAndNothingElse() {
        ExplainRegistry registry = new ExplainRegistry();
        Explainable board = (id, viewer) -> new ExplainReport("board", id, List.of());

        registry.register("board", board);

        assertSame(board, registry.find("board"));
        assertNull(registry.find("tablist"));
        assertEquals(List.of("board"), registry.kinds());
        assertTrue(registry.find("board").explain("hub", null).lines().isEmpty());
    }

    private static List<String> winners(ExplainReport report) {
        return report.lines().stream().filter(ExplainReport.Line::winner).map(ExplainReport.Line::path).toList();
    }
}
