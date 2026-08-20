package art.arcane.gloss.drop;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
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
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
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
    private final NamespacedKey renderedNameKey;
    private final DropNameTracker tracker;
    private final RealDropService realDrops;
    private final Map<String, String> renderedNames;
    private volatile long renderedGeneration = -1L;
    private int pruneTaskId = -1;
    private boolean listening;

    public DropNameService(Gloss plugin) {
        this.plugin = plugin;
        this.nameKey = new NamespacedKey(plugin, "drop_name");
        this.renderedNameKey = new NamespacedKey(plugin, "drop_name_value");
        this.tracker = new DropNameTracker(DropNameService::stillPresent);
        this.realDrops = new RealDropService(plugin);
        this.renderedNames = new ConcurrentHashMap<>();
    }

    public void enable() {
        if (listening) {
            return;
        }

        realDrops.enable();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        listening = true;
        if (plugin.cfg().drops().enabled()) {
            pruneTaskId = plugin.scheduler().sr(this::prunePass, PRUNE_INTERVAL_TICKS);
        }
        rehydrateLoadedChunks();
    }

    public void disable() {
        if (pruneTaskId != -1) {
            plugin.scheduler().csr(pruneTaskId);
            pruneTaskId = -1;
        }
        if (listening) {
            HandlerList.unregisterAll(this);
            listening = false;
        }
        tracker.clear();
        renderedNames.clear();
        realDrops.disable();
    }

    public void reload() {
        if (!listening) {
            enable();
            return;
        }
        if (pruneTaskId != -1) {
            plugin.scheduler().csr(pruneTaskId);
            pruneTaskId = -1;
        }
        tracker.clear();
        renderedNames.clear();
        realDrops.disable();
        realDrops.enable();
        if (plugin.cfg().drops().enabled()) {
            pruneTaskId = plugin.scheduler().sr(this::prunePass, PRUNE_INTERVAL_TICKS);
        }
        rehydrateLoadedChunks();
    }

    public int activeCount() {
        return tracker.size();
    }

    public int activePresentationCount() {
        return realDrops.activeCount();
    }

    public void refresh(Item item) {
        if (!listening || item == null) {
            return;
        }
        plugin.scheduler().runEntity(item, () -> refreshOnOwner(item));
    }

    public void refresh(Item item, String bundleHeaderFormat, String bundleEntryFormat,
                        String bundleMoreFormat, int bundleEntryLimit) {
        if (!listening || item == null || bundleHeaderFormat == null
            || bundleEntryFormat == null || bundleMoreFormat == null) {
            return;
        }

        BundleFormats formats = new BundleFormats(
            bundleHeaderFormat,
            bundleEntryFormat,
            bundleMoreFormat,
            Math.max(1, Math.min(bundleEntryLimit, 10)));
        plugin.scheduler().runEntity(item, () -> refreshOnOwner(item, formats));
    }

    public void remove(Item item) {
        if (item == null) {
            return;
        }
        tracker.forget(item.getUniqueId());
        realDrops.remove(item);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        plugin.scheduler().runEntity(item, () -> refreshOnOwner(item), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        Item source = event.getEntity();
        Item target = event.getTarget();
        remove(source);
        ItemStack targetStack = target.getItemStack();
        applyName(target, targetStack, targetStack.getAmount() + source.getItemStack().getAmount(), null);
        plugin.scheduler().runEntity(target, () -> refreshOnOwner(target), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        remove(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent event) {
        Item item = event.getItem();
        if (event.getRemaining() <= 0) {
            remove(item);
            return;
        }
        plugin.scheduler().runEntity(item, () -> refreshOrRemoveOnOwner(item), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        Item item = event.getItem();
        plugin.scheduler().runEntity(item, () -> refreshOrRemoveOnOwner(item), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Item item) {
                plugin.scheduler().runEntity(item, () -> refreshOnOwner(item));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Item item) {
                remove(item);
            }
        }
    }

    private void applyName(Item item, ItemStack stack, int count, BundleFormats suppliedFormats) {
        GlossConfig.Drops drops = plugin.cfg().drops();
        boolean marked = item.getPersistentDataContainer().has(nameKey, PersistentDataType.BOOLEAN);
        String lastRendered = item.getPersistentDataContainer().get(renderedNameKey, PersistentDataType.STRING);
        boolean glossOwned = DropNameFormatter.ownsExistingName(marked, lastRendered, item.getCustomName());
        if (marked && !glossOwned) {
            clearNameOwnership(item);
            tracker.forget(item.getUniqueId());
        }
        if (!drops.enabled()) {
            if (glossOwned) {
                item.setCustomName(null);
                item.setCustomNameVisible(false);
                tracker.forget(item.getUniqueId());
            }
            clearNameOwnership(item);
            realDrops.present(item, RealDropService.Label.none());
            return;
        }

        if (DropNameFormatter.preservesExistingName(
            drops.preserveCustomNames(), item.getCustomName() != null, glossOwned)) {
            RealDropService.Label preserved = item.isCustomNameVisible()
                ? RealDropService.Label.single(item.getCustomName())
                : RealDropService.Label.none();
            realDrops.present(item, preserved);
            return;
        }

        List<DropNameFormatter.BundleContent> contents = bundleContents(stack);
        String raw = contents.isEmpty()
            ? DropNameFormatter.format(drops.nameFormat(), count, typeLabel(drops, stack))
            : DropNameFormatter.formatBundle(
                drops.bundleFormat(), contents, bundleEntryLimit(suppliedFormats, drops), DropNameService::renderMore);
        String rendered = renderName(raw);
        if (!rendered.equals(item.getCustomName())) {
            item.setCustomName(rendered);
        }
        if (!item.isCustomNameVisible()) {
            item.setCustomNameVisible(true);
        }
        item.getPersistentDataContainer().set(nameKey, PersistentDataType.BOOLEAN, true);
        item.getPersistentDataContainer().set(renderedNameKey, PersistentDataType.STRING, rendered);
        tracker.track(item.getUniqueId());

        List<String> labelLines = verticalLabelLines(contents, suppliedFormats, drops, raw);
        List<String> renderedLines = new ArrayList<>(labelLines.size());
        for (String line : labelLines) {
            renderedLines.add(renderName(line));
        }
        realDrops.present(item, new RealDropService.Label(renderedLines));
    }

    private List<String> verticalLabelLines(List<DropNameFormatter.BundleContent> contents,
                                            BundleFormats suppliedFormats, GlossConfig.Drops drops,
                                            String fallback) {
        if (contents.isEmpty() || !drops.bundleVerticalLabels()) {
            return List.of(fallback);
        }
        BundleFormats formats = suppliedFormats == null
            ? new BundleFormats(
                drops.bundleHeaderFormat(),
                drops.bundleEntryFormat(),
                drops.bundleMoreFormat(),
                drops.bundleEntryLimit())
            : suppliedFormats;
        return DropNameFormatter.formatBundleLines(
            formats.header(), formats.entry(), formats.more(), contents, formats.entryLimit());
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

    private void refreshOnOwner(Item item) {
        GlossConfig.Drops drops = plugin.cfg().drops();
        refreshOnOwner(item, new BundleFormats(
            drops.bundleHeaderFormat(),
            drops.bundleEntryFormat(),
            drops.bundleMoreFormat(),
            drops.bundleEntryLimit()));
    }

    private void refreshOnOwner(Item item, BundleFormats formats) {
        if (!listening || !item.isValid() || item.isDead()) {
            return;
        }
        ItemStack stack = item.getItemStack();
        applyName(item, stack, stack.getAmount(), formats);
    }

    private void refreshOrRemoveOnOwner(Item item) {
        if (!item.isValid() || item.isDead()) {
            remove(item);
            return;
        }
        refreshOnOwner(item);
    }

    private void rehydrateLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                int chunkX = chunk.getX();
                int chunkZ = chunk.getZ();
                Location anchor = new Location(
                    world,
                    (chunkX << 4) + 8,
                    world.getMinHeight(),
                    (chunkZ << 4) + 8);
                plugin.scheduler().runAt(anchor, () -> rehydrateChunk(world, chunkX, chunkZ), 1);
            }
        }
    }

    private void rehydrateChunk(World world, int chunkX, int chunkZ) {
        if (!listening || !world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
            if (entity instanceof Item item) {
                refreshOnOwner(item);
            }
        }
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

    private static List<DropNameFormatter.BundleContent> bundleContents(ItemStack stack) {
        if (stack.getType() != Material.BUNDLE || !(stack.getItemMeta() instanceof BundleMeta meta)) {
            return List.of();
        }

        List<ItemStack> items = meta.getItems();
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        List<DropNameFormatter.BundleContent> contents = new ArrayList<>(items.size());
        for (ItemStack carried : items) {
            if (carried != null) {
                contents.add(new DropNameFormatter.BundleContent(prettyName(carried.getType()), carried.getAmount()));
            }
        }
        return contents;
    }

    private static int bundleEntryLimit(BundleFormats formats, GlossConfig.Drops drops) {
        return formats == null ? drops.bundleEntryLimit() : formats.entryLimit();
    }

    private void clearNameOwnership(Item item) {
        item.getPersistentDataContainer().remove(nameKey);
        item.getPersistentDataContainer().remove(renderedNameKey);
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

    private record BundleFormats(String header, String entry, String more, int entryLimit) {
    }
}
