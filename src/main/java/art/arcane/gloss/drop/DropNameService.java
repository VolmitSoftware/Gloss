package art.arcane.gloss.drop;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DropNameService implements Listener {
    private final Gloss plugin;
    private final NamespacedKey nameKey;
    private final Set<UUID> named;
    private boolean listening;

    public DropNameService(Gloss plugin) {
        this.plugin = plugin;
        this.nameKey = new NamespacedKey(plugin, "drop_name");
        this.named = ConcurrentHashMap.newKeySet();
    }

    public void enable() {
        if (!plugin.cfg().drops().enabled()) {
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        listening = true;
    }

    public void disable() {
        if (!listening) {
            return;
        }

        HandlerList.unregisterAll(this);
        listening = false;
        named.clear();
    }

    public void reload() {
        disable();
        enable();
    }

    public int activeCount() {
        pruneNow();
        return named.size();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        applyName(item, item.getItemStack().getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        named.remove(event.getEntity().getUniqueId());
        Item target = event.getTarget();
        applyName(target, target.getItemStack().getAmount() + event.getEntity().getItemStack().getAmount());
    }

    private void applyName(Item item, int count) {
        GlossConfig.Drops drops = plugin.cfg().drops();
        boolean glossOwned = item.getPersistentDataContainer().has(nameKey, PersistentDataType.BOOLEAN);
        if (DropNameFormatter.preservesExistingName(drops.preserveCustomNames(), item.getCustomName() != null, glossOwned)) {
            return;
        }

        ItemStack stack = item.getItemStack();
        String raw = bundleLabel(stack);
        if (raw.isEmpty()) {
            raw = DropNameFormatter.format(drops.nameFormat(), count, typeLabel(drops, stack));
        }
        item.setCustomName(plugin.text().renderStatic(raw));
        item.setCustomNameVisible(true);
        item.getPersistentDataContainer().set(nameKey, PersistentDataType.BOOLEAN, true);
        named.add(item.getUniqueId());
    }

    private static String typeLabel(GlossConfig.Drops drops, ItemStack stack) {
        String materialName = Form.prettyEnumName(stack.getType().name());
        if (!drops.useItemDisplayNames()) {
            return materialName;
        }

        ItemMeta meta = stack.getItemMeta();
        String displayName = meta != null && meta.hasDisplayName() ? meta.getDisplayName() : null;
        return DropNameFormatter.typeLabel(true, displayName, materialName);
    }

    private String bundleLabel(ItemStack stack) {
        if (stack.getType() != Material.BUNDLE || !(stack.getItemMeta() instanceof BundleMeta meta)) {
            return "";
        }

        List<ItemStack> items = meta.getItems();
        if (items == null || items.isEmpty()) {
            return "";
        }

        List<DropNameFormatter.BundleContent> contents = new ArrayList<>(items.size());
        for (ItemStack carried : items) {
            if (carried == null) {
                continue;
            }
            contents.add(new DropNameFormatter.BundleContent(
                Form.prettyEnumName(carried.getType().name()), carried.getAmount()));
        }

        GlossConfig.Drops drops = plugin.cfg().drops();
        return DropNameFormatter.formatBundle(
            drops.bundleFormat(),
            contents,
            drops.bundleEntryLimit(),
            DropNameService::renderMore);
    }

    private static String renderMore(int remaining) {
        return GlossLocalization.globalText(GlossMessages.DROPS_BUNDLE_MORE,
            GlossLocalization.args(MessageArgument.trusted("count", remaining)));
    }

    private void pruneNow() {
        named.removeIf(entityId -> {
            Entity entity = Bukkit.getEntity(entityId);
            return entity == null || !entity.isValid();
        });
    }
}
