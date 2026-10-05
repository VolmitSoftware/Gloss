package art.arcane.gloss.drop;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.integration.CraftEngineItemNames;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.function.Function;

record DropItemName(String fallback, boolean literal, Function<Player, String> viewerName) {
    static DropItemName capture(ItemStack stack, GlossConfig.RealDrops.Labels labels) {
        String material = DropNameFormatter.typeName(labels.names(), stack.getType().name());
        if (!labels.useItemDisplayNames()) {
            return new DropItemName(material, false, null);
        }
        ItemMeta meta = stack.getItemMeta();
        String displayName = meta == null || !meta.hasDisplayName() ? null : meta.getDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return new DropItemName(displayName, true, null);
        }
        String itemName = meta == null || !meta.hasItemName() ? null : meta.getItemName();
        String fallback = itemName == null || itemName.isBlank() ? material : itemName;
        Plugin craftEngine = Bukkit.getPluginManager().getPlugin("CraftEngine");
        if (craftEngine != null && craftEngine.isEnabled()) {
            try {
                Function<Player, String> resolver = CraftEngineItemNames.capture(stack, craftEngine);
                if (resolver != null) {
                    return new DropItemName(fallback, true, resolver);
                }
            } catch (RuntimeException | LinkageError failure) {
                reportFailure(failure);
            }
        }
        return new DropItemName(fallback, itemName != null && !itemName.isBlank(), null);
    }

    String resolve(Player viewer) {
        if (viewerName == null || viewer == null) {
            return fallback;
        }
        try {
            String name = viewerName.apply(viewer);
            return name == null || name.isBlank() ? fallback : name;
        } catch (RuntimeException | LinkageError failure) {
            reportFailure(failure);
            return fallback;
        }
    }

    private static void reportFailure(Throwable failure) {
        Gloss.logExceptionStackThrottled(false, "drop-name-craftengine", failure,
            "Could not resolve CraftEngine drop names");
    }
}
