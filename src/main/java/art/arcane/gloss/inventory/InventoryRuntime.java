package art.arcane.gloss.inventory;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.config.components.ButtonComponentData;
import art.arcane.gloss.config.components.ComponentData;
import art.arcane.gloss.config.components.DecoComponentData;
import art.arcane.gloss.config.components.ToggleComponentData;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.menu.action.MenuAction;
import org.bukkit.entity.Player;

import java.util.logging.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A compiled inventory document: slots resolved once, actions resolved once, the list source parsed
 * once. Opening a window is then a render, not a parse.
 */
public final class InventoryRuntime {

    private final String id;
    private final InventoryDoc doc;
    private final CompiledCondition selection;
    private final Presentation base;
    private final List<Variant> variants;
    private final ListSection list;

    private InventoryRuntime(String id, InventoryDoc doc, CompiledCondition selection, Presentation base,
                             List<Variant> variants, ListSection list) {
        this.id = id;
        this.doc = doc;
        this.selection = selection;
        this.base = base;
        this.variants = variants;
        this.list = list;
    }

    public static InventoryRuntime compile(String id, InventoryDoc doc) {
        Presentation base = presentation(id, "base", doc.title(), doc.resolveSlots());
        List<Variant> variants = new ArrayList<>(doc.variants().size());
        for (InventoryDoc.Variant variant : doc.variants()) {
            InventoryDoc.Presentation source = variant.presentation();
            Map<Integer, ComponentData> slots = InventoryMask.resolve(doc.width(),
                source.mask().isEmpty() ? doc.mask() : source.mask(),
                source.keys().isEmpty() ? doc.keys() : source.keys(),
                source.slots());
            variants.add(new Variant(variant.id(), variant.priority(),
                ConditionCompiler.compile(new ConditionSource("inventories." + id + ".variants." + variant.id(),
                    variant.when())),
                presentation(id, "variant:" + variant.id(),
                    source.title().isEmpty() ? doc.title() : source.title(), slots)));
        }
        variants.sort(Comparator.comparingInt(Variant::priority).reversed().thenComparing(Variant::id));
        ListSection list = doc.list() == null ? null : listSection(id, doc);
        CompiledCondition selection = ConditionCompiler.compile(
            new ConditionSource("inventories." + id + ".select.when", doc.select().when()));
        return new InventoryRuntime(id, doc, selection, base, List.copyOf(variants), list);
    }

    public String id() {
        return id;
    }

    public InventoryDoc doc() {
        return doc;
    }

    public int selectionPriority() {
        return doc.select().priority();
    }

    public boolean selects(ExprScope scope) {
        return selection.matches(scope);
    }

    public boolean shown(Player viewer) {
        return doc.show().matches(Gloss.instance, viewer);
    }

    public ListSection list() {
        return list;
    }

    /** The presentation this viewer sees: the highest-priority matching variant, else the base. */
    public Presentation present(ExprScope scope) {
        for (Variant variant : variants) {
            if (variant.when().matches(scope)) {
                return variant.presentation();
            }
        }
        return base;
    }

    /** Evaluates the list source for a viewer; a source that is not a list yields no entries. */
    public List<Object> entries(ExprScope scope) {
        if (list == null) {
            return List.of();
        }
        try {
            Object value = ExprEvaluator.eval(list.source(), scope);
            return value instanceof List<?> entries ? List.copyOf(entries) : List.of();
        } catch (RuntimeException failure) {
            Gloss.logThrottled(Level.WARNING, "inventory-list-" + id,
                "Inventory %s list source failed to evaluate: %s", id, failure.getMessage());
            return List.of();
        }
    }

    private static Presentation presentation(String id, String owner, String title,
                                             Map<Integer, ComponentData> slots) {
        Map<Integer, Slot> compiled = new LinkedHashMap<>(slots.size());
        for (Map.Entry<Integer, ComponentData> entry : slots.entrySet()) {
            compiled.put(entry.getKey(), slot(id, owner, entry.getKey(), entry.getValue()));
        }
        return new Presentation(title, Map.copyOf(compiled));
    }

    private static Slot slot(String id, String owner, int index, ComponentData component) {
        return new Slot(index, icon(component), MenuAction.resolve(actions(component),
            "inventory:" + id, owner + "/slot:" + index), component instanceof ButtonComponentData
            || component instanceof ToggleComponentData);
    }

    private static ListSection listSection(String id, InventoryDoc doc) {
        InventoryDoc.ListSection source = doc.list();
        Expr parsed = ExprParser.parse(source.source());
        Slot template = slot(id, "list", -1, source.template());
        return new ListSection(source.var(), parsed, source.pageSize(), source.refreshTicks(),
            template, doc.listSlots(), !ExprEvaluator.isConstant(parsed));
    }

    private static List<art.arcane.gloss.config.action.MenuActionData> actions(ComponentData component) {
        if (component instanceof ButtonComponentData button) {
            return button.actions();
        }
        if (component instanceof ToggleComponentData toggle) {
            return toggle.trueActions();
        }
        return List.of();
    }

    private static MenuIconData icon(ComponentData component) {
        return switch (component) {
            case ButtonComponentData button -> button.iconData();
            case DecoComponentData deco -> deco.iconData();
            case ToggleComponentData toggle -> toggle.trueIcon();
            default -> null;
        };
    }

    /** One drawable, possibly clickable, cell. */
    public record Slot(int index, MenuIconData icon, List<MenuAction<?>> actions, boolean clickable) {
    }

    /** A window as one viewer sees it, before the list fills its area. */
    public record Presentation(String title, Map<Integer, Slot> slots) {
    }

    /** The compiled list section: its parsed source, its cells and its refresh cadence. */
    public record ListSection(String var, Expr source, int pageSize, int refreshTicks, Slot template,
                              List<Integer> slots, boolean viewerDependent) {
    }

    private record Variant(String id, int priority, CompiledCondition when, Presentation presentation) {
    }
}
