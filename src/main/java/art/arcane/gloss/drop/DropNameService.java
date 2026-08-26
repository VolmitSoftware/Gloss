package art.arcane.gloss.drop;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.ParticleLayer;
import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.particle.ParticleFrame;
import art.arcane.gloss.particle.ParticleRect;
import art.arcane.gloss.particle.ParticleText;
import art.arcane.gloss.particle.ParticleTextLayout;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
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
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DropNameService implements Listener {
    private static final int PRUNE_INTERVAL_TICKS = 40;
    private static final int PRUNE_BUDGET = 64;
    private static final int REHYDRATE_CHUNK_BUDGET = 32;
    private static final int RENDER_MEMO_LIMIT = 256;

    private static final Map<Material, String> PRETTY_NAMES = new ConcurrentHashMap<>();

    private final Gloss plugin;
    private final NamespacedKey nameKey;
    private final NamespacedKey renderedNameKey;
    private final DropNameTracker tracker;
    private final ShippedDefaults realDropDefaults;
    private final DocumentRegistry<RealDropSettingsDoc> realDropSettings;
    private final RealDropService realDrops;
    private final Map<String, String> renderedNames;
    private final Map<UUID, Item> trackedItems;
    private final Map<UUID, NativeParticleLabel> nativeParticleLabels;
    private final Deque<LoadedChunk> rehydrateChunks;
    private volatile long renderedGeneration = -1L;
    private volatile long rehydrateGeneration;
    private int pruneTaskId = -1;
    private int particleTaskId = -1;
    private volatile boolean listening;
    private volatile RealDropSettingsDoc realDropDoc;
    private volatile RealDropConditionPlan realDropPlan;

    public DropNameService(Gloss plugin) {
        this.plugin = plugin;
        this.nameKey = new NamespacedKey(plugin, "drop_name");
        this.renderedNameKey = new NamespacedKey(plugin, "drop_name_value");
        this.tracker = new DropNameTracker();
        this.realDropDefaults = new ShippedDefaults(RealDropSettingsDoc.KIND,
            new File(plugin.getDataFolder(), RealDropSettingsDoc.KIND),
            ShippedDocumentCatalog.REAL_DROPS.names());
        this.realDropSettings = DocumentRegistry.folder(RealDropSettingsDoc.KIND,
            new File(plugin.getDataFolder(), RealDropSettingsDoc.KIND),
            RealDropSettingsDoc::parse, RealDropSettingsDoc::revision);
        this.realDrops = new RealDropService(plugin);
        this.renderedNames = new ConcurrentHashMap<>();
        this.trackedItems = new ConcurrentHashMap<>();
        this.nativeParticleLabels = new ConcurrentHashMap<>();
        this.rehydrateChunks = new ArrayDeque<>();
        this.realDropDoc = RealDropSettingsDoc.DEFAULTS;
        this.realDropPlan = compileRealDropPlan(realDropDoc, false);
    }

    public void enable() {
        if (listening) {
            return;
        }

        loadRealDropSettings();
        plugin.watchdog().register(RealDropSettingsDoc.KIND, this::pollRealDropSettings);
        realDrops.enable();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        listening = true;
        if (plugin.cfg().drops().enabled()) {
            pruneTaskId = plugin.scheduler().sr(this::prunePass, PRUNE_INTERVAL_TICKS);
        }
        if (plugin.cfg().particles().enabled()) {
            particleTaskId = plugin.scheduler().sr(this::driveNativeParticles, 1);
        }
        rehydrateLoadedChunks();
    }

    public void disable() {
        cancelRehydration();
        plugin.watchdog().unregister(RealDropSettingsDoc.KIND);
        realDropSettings.close();
        if (pruneTaskId != -1) {
            plugin.scheduler().csr(pruneTaskId);
            pruneTaskId = -1;
        }
        if (particleTaskId != -1) {
            plugin.scheduler().csr(particleTaskId);
            particleTaskId = -1;
        }
        if (listening) {
            HandlerList.unregisterAll(this);
            listening = false;
        }
        tracker.clear();
        trackedItems.clear();
        nativeParticleLabels.clear();
        renderedNames.clear();
        realDrops.disable();
    }

    public void reload() {
        if (!listening) {
            enable();
            return;
        }
        cancelRehydration();
        if (pruneTaskId != -1) {
            plugin.scheduler().csr(pruneTaskId);
            pruneTaskId = -1;
        }
        if (particleTaskId != -1) {
            plugin.scheduler().csr(particleTaskId);
            particleTaskId = -1;
        }
        tracker.clear();
        trackedItems.clear();
        nativeParticleLabels.clear();
        renderedNames.clear();
        loadRealDropSettings();
        realDrops.disable();
        realDrops.enable();
        if (plugin.cfg().drops().enabled()) {
            pruneTaskId = plugin.scheduler().sr(this::prunePass, PRUNE_INTERVAL_TICKS);
        }
        if (plugin.cfg().particles().enabled()) {
            particleTaskId = plugin.scheduler().sr(this::driveNativeParticles, 1);
        }
        rehydrateLoadedChunks();
    }

    public List<String> resetToDefault(String nameOrStar) {
        List<String> restored = realDropDefaults.resetToDefault(nameOrStar);
        if (restored.isEmpty()) {
            return restored;
        }
        loadRealDropSettings();
        reloadPresentations();
        return restored;
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
        plugin.scheduler().runEntity(item, () -> refreshOnOwner(item, "refresh"));
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
        plugin.scheduler().runEntity(item, () -> refreshOnOwner(item, formats, "refresh"));
    }

    public void remove(Item item) {
        if (item == null) {
            return;
        }
        forget(item.getUniqueId());
        nativeParticleLabels.remove(item.getUniqueId());
        realDrops.remove(item);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        plugin.scheduler().runEntity(item, () -> refreshOnOwner(item, "spawn"), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        Item source = event.getEntity();
        Item target = event.getTarget();
        remove(source);
        ItemStack targetStack = target.getItemStack();
        applyName(target, targetStack, targetStack.getAmount() + source.getItemStack().getAmount(), null,
            "merge");
        plugin.scheduler().runEntity(target, () -> refreshOnOwner(target, "merge"), 1);
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
        plugin.scheduler().runEntity(item, () -> refreshOrRemoveOnOwner(item, "pickup"), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        Item item = event.getItem();
        plugin.scheduler().runEntity(item, () -> refreshOrRemoveOnOwner(item, "inventory-pickup"), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Item item) {
                plugin.scheduler().runEntity(item, () -> refreshOnOwner(item, "load"));
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

    private void applyName(Item item, ItemStack stack, int count, BundleFormats suppliedFormats,
                           String eventType) {
        RealDropConditionSnapshot snapshot = RealDropConditionSnapshot.capture(item, eventType);
        RealDropConditionPlan.Selection presentation = realDropPlan.select(plugin, item, snapshot);
        GlossConfig.Drops drops = plugin.cfg().drops();
        boolean marked = item.getPersistentDataContainer().has(nameKey, PersistentDataType.BOOLEAN);
        String lastRendered = item.getPersistentDataContainer().get(renderedNameKey, PersistentDataType.STRING);
        boolean glossOwned = DropNameFormatter.ownsExistingName(marked, lastRendered, item.getCustomName());
        if (marked && !glossOwned) {
            clearNameOwnership(item);
            forget(item.getUniqueId());
        }
        if (!drops.enabled()) {
            if (glossOwned) {
                item.setCustomName(null);
                item.setCustomNameVisible(false);
                forget(item.getUniqueId());
            }
            clearNameOwnership(item);
            nativeParticleLabels.remove(item.getUniqueId());
            realDrops.present(item, RealDropService.Label.none(), presentation);
            return;
        }

        if (DropNameFormatter.preservesExistingName(
            drops.preserveCustomNames(), item.getCustomName() != null, glossOwned)) {
            RealDropService.Label preserved = item.isCustomNameVisible()
                ? RealDropService.Label.rendered(item.getCustomName())
                : RealDropService.Label.none();
            trackNativeParticles(item, preserved, presentation);
            realDrops.present(item, preserved, presentation);
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
        track(item);

        List<String> labelLines = verticalLabelLines(contents, suppliedFormats, drops, raw);
        List<String> renderedLines = new ArrayList<>(labelLines.size());
        for (String line : labelLines) {
            renderedLines.add(renderName(line));
        }
        RealDropService.Label label = new RealDropService.Label(labelLines, renderedLines);
        trackNativeParticles(item, label, presentation);
        realDrops.present(item, label, presentation);
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
            return plugin.text().renderParticleText(null, raw).text();
        }

        String cached = renderedNames.get(raw);
        if (cached != null) {
            return cached;
        }

        String rendered = plugin.text().renderParticleText(null, raw).text();
        if (renderedNames.size() < RENDER_MEMO_LIMIT) {
            String raced = renderedNames.putIfAbsent(raw, rendered);
            return raced == null ? rendered : raced;
        }
        return rendered;
    }

    private void refreshOnOwner(Item item, String eventType) {
        GlossConfig.Drops drops = plugin.cfg().drops();
        refreshOnOwner(item, new BundleFormats(
            drops.bundleHeaderFormat(),
            drops.bundleEntryFormat(),
            drops.bundleMoreFormat(),
            drops.bundleEntryLimit()), eventType);
    }

    private void refreshOnOwner(Item item, BundleFormats formats, String eventType) {
        if (!listening || !item.isValid() || item.isDead()) {
            return;
        }
        ItemStack stack = item.getItemStack();
        applyName(item, stack, stack.getAmount(), formats, eventType);
    }

    private void refreshOrRemoveOnOwner(Item item, String eventType) {
        if (!item.isValid() || item.isDead()) {
            remove(item);
            return;
        }
        refreshOnOwner(item, eventType);
    }

    private void rehydrateLoadedChunks() {
        cancelRehydration();
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                rehydrateChunks.addLast(new LoadedChunk(world, chunk.getX(), chunk.getZ()));
            }
        }
        if (rehydrateChunks.isEmpty()) {
            return;
        }
        long generation = rehydrateGeneration;
        plugin.scheduler().s(() -> drainRehydration(generation), 1);
    }

    private void drainRehydration(long generation) {
        if (!listening || generation != rehydrateGeneration) {
            return;
        }
        int remaining = REHYDRATE_CHUNK_BUDGET;
        while (remaining-- > 0) {
            LoadedChunk loaded = rehydrateChunks.pollFirst();
            if (loaded == null) {
                return;
            }
            Location anchor = new Location(
                loaded.world(),
                (loaded.chunkX() << 4) + 8,
                loaded.world().getMinHeight(),
                (loaded.chunkZ() << 4) + 8);
            plugin.scheduler().runAt(anchor,
                () -> rehydrateChunk(loaded.world(), loaded.chunkX(), loaded.chunkZ(), generation), 1);
        }
        plugin.scheduler().s(() -> drainRehydration(generation), 1);
    }

    private void cancelRehydration() {
        rehydrateGeneration++;
        rehydrateChunks.clear();
    }

    private void loadRealDropSettings() {
        if (plugin.cfg().realDrops().enabled() || plugin.cfg().particles().enabled()) {
            realDropDefaults.extractMissing();
        }
        realDropSettings.reload();
        GlossDocument<RealDropSettingsDoc> document =
            realDropSettings.get(RealDropSettingsDoc.DEFAULT_ID);
        realDropDoc = document == null ? RealDropSettingsDoc.DEFAULTS : document.value();
        refreshRealDropConfig();
    }

    private void pollRealDropSettings() {
        DocumentDelta delta = realDropSettings.poll();
        if (delta.isEmpty()) {
            return;
        }
        GlossDocument<RealDropSettingsDoc> document =
            realDropSettings.get(delta, RealDropSettingsDoc.DEFAULT_ID);
        RealDropSettingsDoc updated = document == null ? RealDropSettingsDoc.DEFAULTS : document.value();
        if (!realDropSettings.dispatch(delta, task -> SchedulerUtils.runGlobal(plugin, task),
            () -> applyRealDropSettings(updated))) {
            Gloss.warnThrottled("real-drop-hotload-scheduling",
                "Could not apply hot-reloaded real-drop settings on the server thread; the change will be retried.");
        }
    }

    private void applyRealDropSettings(RealDropSettingsDoc updated) {
        RealDropSettingsDoc previousDoc = realDropDoc;
        RealDropConditionPlan previousPlan = realDropPlan;
        realDropDoc = updated;
        try {
            refreshRealDropConfig();
            reloadPresentations();
        } catch (RuntimeException | Error failure) {
            realDropDoc = previousDoc;
            realDropPlan = previousPlan;
            throw failure;
        }
    }

    private void refreshRealDropConfig() {
        realDropPlan = compileRealDropPlan(realDropDoc, plugin.cfg().realDrops().enabled());
    }

    private void reloadPresentations() {
        if (!listening) {
            return;
        }
        cancelRehydration();
        realDrops.disable();
        realDrops.enable();
        rehydrateLoadedChunks();
    }

    private void rehydrateChunk(World world, int chunkX, int chunkZ, long generation) {
        if (!listening || generation != rehydrateGeneration || !world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
            if (entity instanceof Item item) {
                refreshOnOwner(item, "rehydrate");
            }
        }
    }

    private static RealDropConditionPlan compileRealDropPlan(RealDropSettingsDoc document,
                                                             boolean enabled) {
        BoundedConditionErrorCallback errors = BoundedConditionErrorCallback.bounded(8, error ->
            Gloss.warn("Real-drop condition %s failed closed: %s", error.path(), error.message()));
        return RealDropConditionPlan.compile(document, enabled, errors);
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
        tracker.inspect(PRUNE_BUDGET, this::inspectTrackedItem);
    }

    private void trackNativeParticles(Item item, RealDropService.Label label,
                                      RealDropConditionPlan.Selection selection) {
        GlossConfig.RealDrops config = selection.style().config();
        UUID itemId = item.getUniqueId();
        if (config.enabled() || config.particleLayers().isEmpty() || label.lines().isEmpty()
            || selection.emptyAudience()) {
            nativeParticleLabels.remove(itemId);
            return;
        }
        nativeParticleLabels.put(itemId, new NativeParticleLabel(
            item, selection, label.authoredText(), label.text()));
    }

    private void driveNativeParticles() {
        if (!listening || nativeParticleLabels.isEmpty()) {
            return;
        }
        long tick = System.currentTimeMillis() / 50L;
        for (NativeParticleLabel state : nativeParticleLabels.values()) {
            if (!emitsOnTick(state.selection().style().config().particleLayers(), tick)) {
                continue;
            }
            Item item = state.item();
            FoliaScheduler.runEntity(plugin, item, () -> emitNativeParticlesOwned(state, tick), 0L,
                () -> nativeParticleLabels.remove(item.getUniqueId(), state));
        }
    }

    private static boolean emitsOnTick(List<ParticleLayer> layers, long tick) {
        for (ParticleLayer layer : layers) {
            if (tick % layer.emission().intervalTicks() == 0L) {
                return true;
            }
        }
        return false;
    }

    private void emitNativeParticlesOwned(NativeParticleLabel state, long tick) {
        Item item = state.item();
        if (!listening || !item.isValid() || item.isDead()
            || nativeParticleLabels.get(item.getUniqueId()) != state) {
            nativeParticleLabels.remove(item.getUniqueId(), state);
            return;
        }
        GlossConfig.RealDrops config = state.selection().style().config();
        double range = Math.max(config.labels().viewRange(), plugin.cfg().particles().viewRange());
        Location origin = item.getLocation().clone().add(0.0D, config.labels().yOffset(), 0.0D);
        for (Entity nearby : item.getNearbyEntities(range, range, range)) {
            if (nearby instanceof Player viewer) {
                plugin.scheduler().runEntity(viewer,
                    () -> emitNativeParticlesForViewer(state, viewer, origin, tick));
            }
        }
    }

    private void emitNativeParticlesForViewer(NativeParticleLabel state, Player viewer,
                                               Location origin, long tick) {
        if (!viewer.isOnline() || !state.selection().visibleTo(plugin, viewer)) {
            return;
        }
        ParticleText.Rendered rendered = state.authored().isEmpty()
            ? new ParticleText.Rendered(state.rendered(), List.of())
            : plugin.text().renderParticleText(viewer, state.authored());
        ParticleFrame frame = nativeLabelFrame(viewer, origin);
        for (ParticleLayer layer : state.selection().style().config().particleLayers()) {
            List<ParticleRect> targets = nativeLabelTargets(layer, rendered);
            if (!layer.target().scope().equals("local") && targets.isEmpty()) {
                continue;
            }
            plugin.particles().emit(viewer, frame, layer, targets, tick);
        }
    }

    private static List<ParticleRect> nativeLabelTargets(ParticleLayer layer,
                                                          ParticleText.Rendered rendered) {
        String scope = layer.target().scope();
        if (scope.equals("projection") || scope.equals("label") || scope.equals("text")) {
            return List.of(ParticleTextLayout.textBounds(rendered.text(), 1.0D));
        }
        if (scope.equals("model")) {
            return List.of(new ParticleRect(0.0D, -0.4D, 0.0D, 0.5D, 0.5D, 0.5D));
        }
        if (scope.equals("line")) {
            List<ParticleRect> lines = ParticleTextLayout.lineBounds(rendered.text(), 1.0D);
            int index = layer.target().line() - 1;
            return index < lines.size() ? List.of(lines.get(index)) : List.of();
        }
        if (scope.equals("span")) {
            boolean perLetter = layer.geometry().type().equals("letterBounds")
                || layer.geometry().type().equals("glyphOutline")
                || layer.geometry().type().equals("glyphFill");
            return ParticleTextLayout.bounds(rendered, layer.target().name(), 1.0D, perLetter);
        }
        return List.of();
    }

    private static ParticleFrame nativeLabelFrame(Player viewer, Location origin) {
        Vector front = viewer.getEyeLocation().toVector().subtract(origin.toVector());
        if (front.lengthSquared() < 1.0E-12D) {
            front = new Vector(0.0D, 0.0D, 1.0D);
        }
        front.normalize();
        Vector referenceUp = Math.abs(front.getY()) > 0.999D
            ? new Vector(0.0D, 0.0D, 1.0D)
            : new Vector(0.0D, 1.0D, 0.0D);
        Vector right = front.clone().crossProduct(referenceUp).normalize();
        Vector up = right.clone().crossProduct(front).normalize();
        return new ParticleFrame(origin, right, up, front.clone().multiply(-1.0D));
    }

    private void inspectTrackedItem(UUID entityId) {
        Item item = trackedItems.get(entityId);
        if (item == null) {
            tracker.forget(entityId);
            return;
        }
        Runnable inspect = () -> {
            if (!listening || !item.isValid() || item.isDead()) {
                forget(entityId);
            }
        };
        Runnable retired = () -> forget(entityId);
        if (!FoliaScheduler.runEntity(plugin, item, inspect, 0L, retired)) {
            retired.run();
        }
    }

    private void track(Item item) {
        UUID entityId = item.getUniqueId();
        trackedItems.put(entityId, item);
        tracker.track(entityId);
    }

    private void forget(UUID entityId) {
        tracker.forget(entityId);
        trackedItems.remove(entityId);
        nativeParticleLabels.remove(entityId);
    }

    private record BundleFormats(String header, String entry, String more, int entryLimit) {
    }

    private record LoadedChunk(World world, int chunkX, int chunkZ) {
    }

    private record NativeParticleLabel(Item item, RealDropConditionPlan.Selection selection,
                                       String authored, String rendered) {
    }
}
