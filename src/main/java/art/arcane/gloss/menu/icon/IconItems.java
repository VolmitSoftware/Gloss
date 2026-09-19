package art.arcane.gloss.menu.icon;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.icon.BlockIconData;
import art.arcane.gloss.config.icon.CustomItemIconData;
import art.arcane.gloss.config.icon.ItemIconData;
import art.arcane.gloss.config.icon.ItemStackIconData;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.config.icon.PlayerHeadIconData;
import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.gloss.integration.ItemProviderRegistry;
import art.arcane.gloss.profile.PlayerHeadItems;
import art.arcane.gloss.profile.PlayerHeadLookup;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The stack behind an icon, for the surfaces that draw items instead of display entities: chest
 * slots and the give/take actions. Only the icon kinds that have an item form
 * resolve here; everything else is the caller's degradation to choose, so this returns null rather
 * than guessing one.
 */
public final class IconItems {
    private IconItems() {
    }

    /**
     * @return the stack this icon draws, or null when the icon has no item form or its item could
     *     not be resolved (an unknown material, an absent custom-item provider)
     */
    public static ItemStack resolve(MenuIconData icon, Player viewer) {
        if (icon == null) {
            return null;
        }
        try {
            return switch (icon) {
                case ItemIconData data -> item(data);
                case ItemStackIconData data -> data.stack() == null ? null : data.stack().clone();
                case BlockIconData data -> new ItemStack(data.requireBlock());
                case CustomItemIconData data -> customItem(data);
                case PlayerHeadIconData data -> playerHead(data, viewer);
                default -> null;
            };
        } catch (MenuIconException | RuntimeException | LinkageError unresolvable) {
            return null;
        }
    }

    private static ItemStack item(ItemIconData data) throws MenuIconException {
        Material material = data.requireMaterial();
        ItemStack stack = new ItemStack(material, Math.max(1, data.count()));
        return data.customModelValue() > 0 ? withModelData(stack, data.customModelValue()) : stack;
    }

    private static ItemStack withModelData(ItemStack stack, int modelData) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.setCustomModelData(modelData);
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack customItem(CustomItemIconData data) {
        Gloss plugin = Gloss.instance;
        ItemProviderRegistry registry = plugin == null ? null : plugin.getItemProviders();
        ItemStack resolved = registry == null ? null : registry.resolve(data.provider(), data.item());
        if (resolved == null) {
            return null;
        }
        resolved.setAmount(Math.max(1, data.count()));
        return resolved;
    }

    private static ItemStack playerHead(PlayerHeadIconData data, Player viewer) {
        PlayerHeadLookup lookup = PlayerHeadMenuIcon.lookupFor(viewer, data);
        return PlayerHeadItems.stackFor(lookup, null);
    }
}
