package art.arcane.gloss.drop;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.text.TextPipeline;
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
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DropNameService implements Listener {
    private static final int PRUNE_INTERVAL_TICKS = 40;
    private static final int PRUNE_BUDGET = 64;
    private static final int RENDER_MEMO_LIMIT = 256;

    private static final Map<Material, String> PRETTY_NAMES = new ConcurrentHashMap<>();

    private final Gloss plugin;
    private final NamespacedKey nameKey;
    private final DropNameTracker tracker;
    private final Map<String, String> renderedNames;
    private volatile long renderedGeneration = -1L;
    private int pruneTaskId = -1;
    private boolean listening;

    public DropNameService(Gloss plugin) {
        this.plugin = plugin;
        this.nameKey = new NamespacedKey(plugin, "drop_name");
        this.tracker = new DropNameTracker(DropNameService::stillPresent);
        this.renderedNames = new ConcurrentHashMap<>();
    }

    public void enable() {
        if (!plugin.cfg().drops().enabled()) {
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        listening = true;
        pruneTaskId = plugin.scheduler().sr(this::prunePass, PRUNE_INTERVAL_TICKS);
    }

    public void disable() {
        if (!listening) {
            return;
        }

        if (pruneTaskId != -1) {
            plugin.scheduler().csr(pruneTaskId);
            pruneTaskId = -1;
        }
        HandlerList.unregisterAll(this);
        listening = false;
        tracker.clear();
        renderedNames.clear();
    }

    public void reload() {
        disable();
        renderedNames.clear();
        enable();
    }

    public int activeCount() {
        return tracker.size();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        ItemStack stack = item.getItemStack();
        applyName(item, stack, stack.getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        tracker.forget(event.getEntity().getUniqueId());
        Item target = event.getTarget();
        ItemStack stack = target.getItemStack();
        applyName(target, stack, stack.getAmount() + event.getEntity().getItemStack().getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        tracker.forget(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent event) {
        if (event.getRemaining() > 0) {
            return;
        }
        tracker.forget(event.getItem().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        tracker.forget(event.getItem().getUniqueId());
    }

    private void applyName(Item item, ItemStack stack, int count) {
        GlossConfig.Drops drops = plugin.cfg().drops();
        boolean glossOwned = item.getPersistentDataContainer().has(nameKey, PersistentDataType.BOOLEAN);
        if (DropNameFormatter.preservesExistingName(drops.preserveCustomNames(), item.getCustomName() != null, glossOwned)) {
            return;
        }

        String raw = bundleLabel(drops, stack);
        if (raw.isEmpty()) {
            raw = DropNameFormatter.format(drops.nameFormat(), count, typeLabel(drops, stack));
        }
        item.setCustomName(renderName(raw));
        if (!item.isCustomNameVisible()) {
            item.setCustomNameVisible(true);
        }
        if (!glossOwned) {
            item.getPersistentDataContainer().set(nameKey, PersistentDataType.BOOLEAN, true);
        }
        tracker.track(item.getUniqueId());
    }

    private String renderName(String raw) {
        long generation = TextPipeline.emojiGeneration();
        if (generation != renderedGeneration) {
            renderedNames.clear();
            renderedGeneration = generation;
        }

        int flags = TextPipeline.classify(raw);
        if ((flags & (TextPipeline.HAS_FUNCTION | TextPipeline.HAS_PLACEHOLDER)) != 0) {
            return plugin.text().renderStatic(raw);
        }

        String cached = renderedNames.get(raw);
        if (cached != null) {
            return cached;
        }

        String rendered = plugin.text().renderStatic(raw);
        if (renderedNames.size() < RENDER_MEMO_LIMIT) {
            String raced = renderedNames.putIfAbsent(raw, rendered);
            return raced == null ? rendered : raced;
        }
        return rendered;
    }

    private static String typeLabel(GlossConfig.Drops drops, ItemStack stack) {
        String materialName = prettyName(stack.getType());
        if (!drops.useItemDisplayNames()) {
            return materialName;
        }

        ItemMeta meta = stack.getItemMeta();
        String displayName = meta != null && meta.hasDisplayName() ? meta.getDisplayName() : null;
        return DropNameFormatter.typeLabel(true, displayName, materialName);
    }

    private static String bundleLabel(GlossConfig.Drops drops, ItemStack stack) {
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
            contents.add(new DropNameFormatter.BundleContent(prettyName(carried.getType()), carried.getAmount()));
        }

        return DropNameFormatter.formatBundle(
            drops.bundleFormat(),
            contents,
            drops.bundleEntryLimit(),
            DropNameService::renderMore);
    }

    private static String prettyName(Material material) {
        return PRETTY_NAMES.computeIfAbsent(material, key -> Form.prettyEnumName(key.name()));
    }

    private static String renderMore(int remaining) {
        return GlossLocalization.globalText(GlossMessages.DROPS_BUNDLE_MORE,
            GlossLocalization.args(MessageArgument.trusted("count", remaining)));
    }

    private void prunePass() {
        tracker.prune(PRUNE_BUDGET);
    }

    private static boolean stillPresent(UUID entityId) {
        Entity entity = Bukkit.getEntity(entityId);
        return entity != null && entity.isValid();
    }
}
