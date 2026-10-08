package art.arcane.gloss.tab;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExprScope;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class TablistLayoutRuntime {
    public static final String SLOT_NAME_PREFIX = " gloss_slot_";
    public static final String SLOT_ID_PREFIX = "gloss:tab:";
    public static final int BASE_LIST_ORDER = 100_000;

    private final TablistLayoutDefinition.LayoutPresentation presentation;
    private final ShowCondition show;
    private final List<Cell> cells;
    private final List<Section> sections;
    private final List<Variant> variants;

    private TablistLayoutRuntime(Compilation compilation) {
        this.presentation = compilation.presentation();
        this.show = compilation.show();
        this.cells = compilation.cells();
        this.sections = compilation.sections();
        this.variants = compilation.variants();
    }

    public static TablistLayoutRuntime compile(TablistDoc.Layout layout) {
        if (layout == null || !layout.active()) {
            return null;
        }
        List<Variant> variants = new ArrayList<>(layout.variants().size());
        for (TablistLayoutDefinition.LayoutVariant variant : layout.variants()) {
            variants.add(new Variant(variant.id(), variant.priority(), ConditionCompiler.compile(
                new ConditionSource("tablist.layout.variants." + variant.id() + ".when", variant.when())),
                compilePresentation(variant.presentation(), ShowCondition.ALWAYS, List.of())));
        }
        variants.sort(Comparator.comparingInt(Variant::priority).reversed().thenComparing(Variant::id));
        return compilePresentation(layout.presentation(), layout.show(), List.copyOf(variants));
    }

    private static TablistLayoutRuntime compilePresentation(TablistLayoutDefinition.LayoutPresentation presentation,
                                                            ShowCondition show, List<Variant> variants) {
        List<Cell> cells = new ArrayList<>(presentation.entries());
        for (int index = 0; index < presentation.entries(); index++) {
            cells.add(new Cell(index, slotId(index), slotName(index), listOrderFor(index), "", null, null, true));
        }
        for (TablistLayoutDefinition.Slot slot : presentation.slots()) {
            int index = indexOf(slot.column(), slot.row(), presentation.rows());
            cells.set(index, new Cell(index, slotId(index), slotName(index), listOrderFor(index), slot.text(),
                slot.skin(), slot.ping(), slot.hat()));
        }
        List<Section> sections = new ArrayList<>(presentation.sections().size());
        for (TablistLayoutDefinition.Section section : presentation.sections()) {
            List<Cell> playerCells = new ArrayList<>(section.columns() * section.rows());
            for (int column = section.column(); column < section.column() + section.columns(); column++) {
                for (int row = section.row(); row < section.row() + section.rows(); row++) {
                    playerCells.add(cells.get(indexOf(column, row, presentation.rows())));
                }
            }
            List<SortKey> keys = new ArrayList<>(section.sort().size());
            for (TablistLayoutDefinition.SortKey key : section.sort()) {
                keys.add(new SortKey(ExprParser.parse(key.expression()), key.type().equals("number"),
                    key.direction().equals("descending")));
            }
            sections.add(new Section(section, List.copyOf(playerCells), ConditionCompiler.compile(
                new ConditionSource("tablist.layout.sections." + section.id() + ".filter", section.filter())), List.copyOf(keys)));
        }
        return new TablistLayoutRuntime(new Compilation(presentation, show, List.copyOf(cells),
            List.copyOf(sections), variants));
    }

    public TablistLayoutRuntime select(ExprScope scope, BoundedConditionErrorCallback errors) {
        if (!show.matches(scope, errors)) {
            return null;
        }
        for (Variant variant : variants) {
            if (variant.condition().matches(scope, errors)) {
                return variant.runtime();
            }
        }
        return this;
    }

    public static int indexOf(int column, int row, int rows) {
        return column * rows + row;
    }

    public static UUID slotId(int index) {
        return UUID.nameUUIDFromBytes((SLOT_ID_PREFIX + index).getBytes(StandardCharsets.UTF_8));
    }

    public static String slotName(int index) {
        return SLOT_NAME_PREFIX + index;
    }

    public static int listOrderFor(int index) {
        return BASE_LIST_ORDER - index;
    }

    public int size() {
        return cells.size();
    }

    public Cell cell(int index) {
        return cells.get(index);
    }

    public List<Section> sections() {
        return sections;
    }

    public TablistLayoutDefinition.Skin skin(String name) {
        return name == null ? null : presentation.skins().get(name);
    }

    public record Cell(int index, UUID id, String name, int listOrder, String text, String skin, Integer ping,
                       boolean hat) {
    }

    public record Section(TablistLayoutDefinition.Section source, List<Cell> cells, CompiledCondition filter, List<SortKey> sort) {
    }

    public record SortKey(Expr expression, boolean number, boolean descending) {
    }

    private record Variant(String id, int priority, CompiledCondition condition, TablistLayoutRuntime runtime) {
    }

    private record Compilation(TablistLayoutDefinition.LayoutPresentation presentation, ShowCondition show, List<Cell> cells,
                                List<Section> sections, List<Variant> variants) {
    }
}
