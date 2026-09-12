package art.arcane.gloss.inventory;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.icon.BlockIconData;
import art.arcane.gloss.config.icon.CustomItemIconData;
import art.arcane.gloss.config.icon.ItemIconData;
import art.arcane.gloss.config.icon.ItemStackIconData;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.config.icon.PlayerHeadIconData;
import art.arcane.gloss.config.icon.TextIconData;
import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.menu.icon.IconItems;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.bukkit.registry.RegistryUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Draws one icon into one chest slot. Six of the nine icon kinds have an item form; the three that
 * are display-entity surfaces (text images, animated text images and entities) have none, so they
 * draw the configured stand-in with a lore line saying why rather than leaving a hole in the window.
 */
public final class SlotItemRenderer {
    /** The head a player-head icon draws before, and without, a profile lookup. */
    public static final Material UNRESOLVED_HEAD = Material.PLAYER_HEAD;
    private static final Material DEFAULT_UNSUPPORTED = Material.PAPER;

    private SlotItemRenderer() {
    }

    /** What a slot draws, decided before any Bukkit stack is assembled. */
    public record SlotItem(Material material, int amount, String name, List<String> lore,
                           ItemStack prebuilt, boolean unsupported) {
        public SlotItem {
            lore = lore == null ? List.of() : List.copyOf(lore);
        }
    }

    /** The stack for this icon, with its name and lore already through the text pipeline. */
    public static ItemStack render(Player viewer, MenuIconData icon, ExprScope scope) {
        SlotItem slot = resolve(viewer, icon, scope);
        ItemStack stack = slot.prebuilt() != null ? slot.prebuilt().clone() : new ItemStack(slot.material());
        stack.setAmount(Math.max(1, slot.amount()));
        String name = slot.name();
        List<String> lore = slot.lore();
        if (name == null && lore.isEmpty()) {
            return stack;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        if (name != null) {
            meta.setDisplayName(name);
        }
        if (!lore.isEmpty()) {
            meta.setLore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Decides what the slot draws without touching the item registry, so the decision is testable
     * and one broken icon cannot take a whole window down with it.
     */
    public static SlotItem resolve(Player viewer, MenuIconData icon, ExprScope scope) {
        if (icon == null) {
            return unsupported(viewer, "missing");
        }
        return switch (icon) {
            case ItemIconData data -> item(viewer, scope, data);
            case ItemStackIconData data -> data.stack() == null
                ? unsupported(viewer, "itemStack")
                : new SlotItem(data.stack().getType(), data.stack().getAmount(), null, List.of(),
                    data.stack(), false);
            case BlockIconData data -> block(viewer, data);
            case CustomItemIconData data -> customItem(viewer, data);
            case PlayerHeadIconData data -> playerHead(viewer, data);
            case TextIconData data -> new SlotItem(Material.PAPER, 1,
                text(viewer, scope, data.text()), List.of(), null, false);
            default -> unsupported(viewer, icon.getType().getSerializedName());
        };
    }

    /** The stand-in an icon with no item form draws; an operator knob, defaulting to paper. */
    public static Material defaultUnsupportedMaterial() {
        String configured = GlossConfig.current().modules().inventories().unsupportedIconItem();
        if (configured == null || configured.isBlank()) {
            return DEFAULT_UNSUPPORTED;
        }
        try {
            NamespacedKey key = NamespacedKey.fromString(configured.trim());
            Material resolved = key == null ? null : RegistryUtil.find(Material.class, key);
            return resolved == null ? DEFAULT_UNSUPPORTED : resolved;
        } catch (RuntimeException | LinkageError unavailableRegistry) {
            return DEFAULT_UNSUPPORTED;
        }
    }

    private static SlotItem item(Player viewer, ExprScope scope, ItemIconData data) {
        Material material;
        try {
            material = data.requireMaterial();
        } catch (MenuIconException unknownMaterial) {
            return unsupported(viewer, "item");
        }
        return new SlotItem(material, Math.max(1, data.count()),
            text(viewer, scope, data.name()), lore(viewer, scope, data.lore()), null, false);
    }

    /**
     * A block draws as its item form. {@code requireBlock} asks the block registry whether the
     * material really is a block, which is unavailable outside a server; when the registry cannot
     * answer, the authored material is drawn as declared rather than degrading a correct icon.
     */
    private static SlotItem block(Player viewer, BlockIconData data) {
        try {
            return new SlotItem(data.requireBlock(), 1, null, List.of(), null, false);
        } catch (MenuIconException unknownBlock) {
            return unsupported(viewer, "block");
        } catch (RuntimeException | LinkageError unavailableRegistry) {
            return data.blockType() == null
                ? unsupported(viewer, "block")
                : new SlotItem(data.blockType(), 1, null, List.of(), null, false);
        }
    }

    private static SlotItem customItem(Player viewer, CustomItemIconData data) {
        ItemStack resolved = IconItems.resolve(data, viewer);
        if (resolved == null) {
            return unsupported(viewer, "customItem");
        }
        return new SlotItem(resolved.getType(), Math.max(1, data.count()), null, List.of(), resolved, false);
    }

    private static SlotItem playerHead(Player viewer, PlayerHeadIconData data) {
        ItemStack resolved = IconItems.resolve(data, viewer);
        return resolved == null
            ? new SlotItem(UNRESOLVED_HEAD, 1, null, List.of(), null, false)
            : new SlotItem(resolved.getType(), 1, null, List.of(), resolved, false);
    }

    private static SlotItem unsupported(Player viewer, String kind) {
        String note = Gloss.instance == null || Gloss.instance.getLocalization() == null
            ? kind
            : Gloss.instance.getLocalization().text(GlossMessages.FORMS_ICON_UNSUPPORTED,
                MessageArgs.builder().untrusted("kind", kind).build());
        return new SlotItem(defaultUnsupportedMaterial(), 1, null, List.of(note), null, true);
    }

    private static List<String> lore(Player viewer, ExprScope scope, List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> rendered = new ArrayList<>(raw.size());
        for (String line : raw) {
            rendered.add(text(viewer, scope, line));
        }
        return rendered;
    }

    private static String text(Player viewer, ExprScope scope, String raw) {
        if (raw == null) {
            return null;
        }
        Gloss plugin = Gloss.instance;
        TextPipeline pipeline = plugin == null ? null : plugin.text();
        if (pipeline == null) {
            return raw;
        }
        return scope == null
            ? TextPipeline.menuText(viewer, raw)
            : pipeline.renderScoped(viewer, raw, scope, UnaryOperator.identity());
    }
}
