package art.arcane.gloss.behavior;

import art.arcane.gloss.board.BoardDoc;
import art.arcane.gloss.board.GlossBoardMeta;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.tab.TablistDoc;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds an {@link ExplainReport} for the three runtimes this lane knows how to explain. Each one
 * re-evaluates the document's own conditions against the viewer's scope: the command runs once per
 * operator request, so it compiles on the spot instead of reaching into a runtime's private cache.
 */
public final class ExplainReports {
    private ExplainReports() {
    }

    /** Boards: the visibility gate, the selection gate, then the variants in priority order. */
    public static ExplainReport board(GlossBoardMeta meta, ExprScope scope) {
        List<ExplainReport.Line> lines = new ArrayList<>();
        lines.add(line("show", meta.show().expression(), meta.show().matches(scope), false));
        lines.add(line("select.when", meta.selection().when(),
            evaluate("select.when", meta.selection().when(), scope), false));
        List<BoardDoc.Variant> variants = new ArrayList<>(meta.variants());
        variants.sort((left, right) -> right.priority() == left.priority()
            ? left.id().compareTo(right.id()) : Integer.compare(right.priority(), left.priority()));
        boolean decided = false;
        for (BoardDoc.Variant variant : variants) {
            boolean matched = evaluate("variants." + variant.id() + ".when", variant.when(), scope);
            boolean winner = matched && !decided;
            decided |= matched;
            lines.add(line("variants." + variant.id() + ".when", variant.when(), matched, winner));
        }
        if (!decided) {
            lines.add(new ExplainReport.Line("base", "-", "-", true));
        }
        return new ExplainReport("board", meta.id(), lines);
    }

    /** Tablist: the document gate, then the header/footer and list-name sections in turn. */
    public static ExplainReport tablist(TablistDoc doc, ExprScope scope) {
        List<ExplainReport.Line> lines = new ArrayList<>();
        lines.add(line("show", doc.show().expression(), doc.show().matches(scope), false));
        lines.add(line("headerFooter.show", doc.headerFooter().show().expression(),
            doc.headerFooter().show().matches(scope), false));
        boolean decided = false;
        List<TablistDoc.HeaderFooterVariant> headerVariants = new ArrayList<>(doc.headerFooter().variants());
        headerVariants.sort((left, right) -> right.priority() == left.priority()
            ? left.id().compareTo(right.id()) : Integer.compare(right.priority(), left.priority()));
        for (TablistDoc.HeaderFooterVariant variant : headerVariants) {
            String path = "headerFooter.variants." + variant.id() + ".when";
            boolean matched = evaluate(path, variant.when(), scope);
            lines.add(line(path, variant.when(), matched, matched && !decided));
            decided |= matched;
        }
        if (!decided) {
            lines.add(new ExplainReport.Line("headerFooter.base", "-", "-", true));
        }
        lines.add(line("listNames.show", doc.listNames().show().expression(),
            doc.listNames().show().matches(scope), false));
        decided = false;
        List<TablistDoc.ListNameVariant> nameVariants = new ArrayList<>(doc.listNames().variants());
        nameVariants.sort((left, right) -> right.priority() == left.priority()
            ? left.id().compareTo(right.id()) : Integer.compare(right.priority(), left.priority()));
        for (TablistDoc.ListNameVariant variant : nameVariants) {
            String path = "listNames.variants." + variant.id() + ".when";
            boolean matched = evaluate(path, variant.when(), scope);
            lines.add(line(path, variant.when(), matched, matched && !decided));
            decided |= matched;
        }
        if (!decided) {
            lines.add(new ExplainReport.Line("listNames.base", "-", "-", true));
        }
        return new ExplainReport("tablist", TablistDoc.KIND, lines);
    }

    /** Behaviors: every entry's {@code when} gate; unlike a variant list, each open gate runs. */
    public static ExplainReport behavior(BehaviorRuntime runtime, ExprScope scope) {
        List<ExplainReport.Line> lines = new ArrayList<>();
        for (BehaviorRuntime.CompiledEntry entry : runtime.entries()) {
            String path = "on[" + entry.index() + "].when";
            String expression = entry.entry().when() == null ? "true" : entry.entry().when();
            boolean matched = entry.when() == null || entry.when().matches(scope, entry.errors());
            lines.add(line(path, expression, matched, matched));
        }
        return new ExplainReport("behavior", runtime.id(), lines);
    }

    private static ExplainReport.Line line(String path, String expression, boolean matched, boolean winner) {
        return new ExplainReport.Line(path, expression, Boolean.toString(matched), winner);
    }

    private static boolean evaluate(String path, String expression, ExprScope scope) {
        CompiledCondition condition = ConditionCompiler.compile(new ConditionSource(path, expression));
        return condition.matches(scope);
    }
}
