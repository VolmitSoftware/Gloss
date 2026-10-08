package art.arcane.gloss.inventory;

import art.arcane.gloss.config.icon.BlockIconData;
import art.arcane.gloss.config.icon.CustomItemIconData;
import art.arcane.gloss.config.icon.ItemIconData;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.config.icon.PlayerHeadIconData;
import art.arcane.gloss.config.icon.TextIconData;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.text.TextPipeline;

import java.util.List;
import java.util.Map;

public record InventoryRefreshPlan(int titleTicks, int slotsTicks, int conditionsTicks, int listTicks) {
    public static final int TITLE = 1;
    public static final int SLOTS = 2;
    public static final int CONDITIONS = 4;
    public static final int LIST = 8;
    public static final int ALL = TITLE | SLOTS | CONDITIONS | LIST;

    static InventoryRefreshPlan compile(InventoryDoc doc, InventoryRuntime.Presentation base,
                                        List<InventoryRuntime.Presentation> variants, boolean dynamicList) {
        InventoryRefreshPolicy policy = doc.refresh();
        boolean always = policy.mode().equals("always");
        boolean title = always || TextPipeline.viewerDependent(base.title());
        boolean slots = always || dynamic(base.slots());
        boolean conditions = always || doc.show().isDynamic();
        for (InventoryRuntime.Presentation variant : variants) {
            title |= TextPipeline.viewerDependent(variant.title());
            slots |= dynamic(variant.slots());
        }
        for (InventoryDoc.Variant variant : doc.variants()) {
            conditions |= !ExprEvaluator.isConstant(ExprParser.parse(variant.when()));
        }
        return new InventoryRefreshPlan(title ? policy.titleTicks() : 0, slots ? policy.slotsTicks() : 0,
            conditions ? policy.conditionsTicks() : 0,
            doc.list() != null && (always || dynamicList) ? policy.resolvedListTicks(doc.list()) : 0);
    }

    public boolean active() {
        return titleTicks > 0 || slotsTicks > 0 || conditionsTicks > 0 || listTicks > 0;
    }

    static boolean dynamic(MenuIconData icon) {
        return switch (icon) {
            case TextIconData text -> TextPipeline.viewerDependent(text.text());
            case ItemIconData item -> TextPipeline.viewerDependent(item.name()) || dynamic(item.lore());
            case BlockIconData block -> TextPipeline.viewerDependent(block.name()) || dynamic(block.lore());
            case PlayerHeadIconData head -> head.resolvedRefreshTicks() > 0
                || TextPipeline.viewerDependent(head.name()) || dynamic(head.lore());
            case CustomItemIconData ignored -> true;
            case null, default -> false;
        };
    }

    private static boolean dynamic(List<String> lines) {
        for (String line : lines) {
            if (TextPipeline.viewerDependent(line)) {
                return true;
            }
        }
        return false;
    }

    static boolean dynamic(InventoryRuntime.Slot slot) {
        return dynamic(slot.icon()) || slot.toggle() != null &&
            (TextPipeline.viewerDependent(slot.toggle().condition())
                || TextPipeline.viewerDependent(slot.toggle().expectedValue())
                || dynamic(slot.toggle().falseIcon()));
    }

    private static boolean dynamic(Map<Integer, InventoryRuntime.Slot> slots) {
        for (InventoryRuntime.Slot slot : slots.values()) {
            if (dynamic(slot)) {
                return true;
            }
        }
        return false;
    }
}
