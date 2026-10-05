package art.arcane.gloss.drop;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.ParticleLayer;
import art.arcane.gloss.api.ParticleTextSpan;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.RegistryOwner;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.hologram.AnimationTemplate;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.particle.ParticleFrame;
import art.arcane.gloss.particle.ParticleRect;
import art.arcane.gloss.particle.ParticleText;
import art.arcane.gloss.particle.ParticleTextLayout;
import art.arcane.gloss.text.TextPipeline;
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
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.function.Function;

public final class DropNameService implements Listener, RegistryOwner {
    private static final int PRUNE_INTERVAL_TICKS = 40;
    private static final int PRUNE_BUDGET = 64;
    private static final int REHYDRATE_CHUNK_BUDGET = 32;
    private static final int RENDER_MEMO_LIMIT = 256;

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
    private final Map<UUID, ConditionalLabel> conditionalLabels = new ConcurrentHashMap<>();
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
        this.rehydrateChunks = new ConcurrentLinkedDeque<>();
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
        clearConditionalLabels();
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
        clearConditionalLabels();
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
    public void onEntityRemove(EntityRemoveEvent event) {
        if (event.getEntity() instanceof Item item) {
            remove(item);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        realDrops.forgetViewer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        realDrops.forgetViewer(event.getPlayer().getUniqueId());
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
        RealDropConditionPlan plan = realDropPlan;
        RealDropConditionSnapshot snapshot = RealDropConditionSnapshot.capture(item, eventType, plan.fields());
        RealDropConditionPlan.Selection presentation = plan.select(plugin, item, snapshot);
        GlossConfig.Drops drops = plugin.cfg().drops();
        GlossConfig.RealDrops.Labels labels = presentation.style().config().labels();
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
            labels.preserveCustomNames(), item.getCustomName() != null, glossOwned)) {
            RealDropService.Label preserved = realDrops.preservedLabel(item);
            trackNativeParticles(item, preserved, presentation);
            realDrops.present(item, preserved, presentation);
            return;
        }

        LabelPresentation namedLabel = buildLabel(stack, count, suppliedFormats, labels);
        String rendered = namedLabel.nativeName();
        if (!rendered.equals(item.getCustomName())) {
            item.setCustomName(rendered);
        }
        if (!item.isCustomNameVisible()) {
            item.setCustomNameVisible(true);
        }
        item.getPersistentDataContainer().set(nameKey, PersistentDataType.BOOLEAN, true);
        item.getPersistentDataContainer().set(renderedNameKey, PersistentDataType.STRING, rendered);
        track(item);

        RealDropService.Label label = namedLabel.label();
        trackNativeParticles(item, label, presentation);
        if (!labels.show().isAlwaysVisible()) {
            item.setCustomNameVisible(false);
        }
        realDrops.present(item, labels.show().isAlwaysVisible() ? label : RealDropService.Label.none(), presentation);
        applyStyledLabel(item, label, presentation);
    }

    private void applyStyledLabel(Item item, RealDropService.Label label,
                                       RealDropConditionPlan.Selection selection) {
        UUID itemId = item.getUniqueId();
        ShowCondition show = selection.style().config().labels().show();
        if (show.isAlwaysVisible() && selection.style().config().enabled()) {
            removeConditionalLabel(itemId);
            return;
        }
        item.setCustomNameVisible(false);
        nativeParticleLabels.remove(itemId);
        if ((!show.isAlwaysVisible() && !show.isDynamic()) || label.lines().isEmpty()
            || !selection.style().config().labels().enabled()) {
            removeConditionalLabel(itemId);
            return;
        }
        ConditionalLabel current = conditionalLabels.get(itemId);
        if (current == null) {
            TemporaryHologram hologram = plugin.holograms().createTemporary(
                "drop-label-" + itemId, item.getLocation(), Long.MAX_VALUE);
            current = new ConditionalLabel(hologram, selection);
            conditionalLabels.put(itemId, current);
            ConditionalLabel bound = current;
            plugin.holograms().setViewerCondition(hologram,
                viewer -> bound.selection.style().config().labels().show().matches(
                    new GlossConditionScope(plugin, bound.selection.snapshot().viewerContext(viewer)))
                    && (!bound.selection.style().config().enabled() || bound.selection.visibleTo(plugin, viewer)));
            hologram.bindPosition(item, () -> {
                if (!listening || !item.isValid() || item.isDead()) {
                    forget(itemId);
                    return null;
                }
                bound.selection = bound.selection.refreshSnapshot(item);
                return item.getLocation().add(0.0D, bound.selection.style().config().labels().yOffset(), 0.0D);
            });
        }
        current.selection = selection;
        current.hologram.setStyle(selection.style().config().labels().style());
        current.hologram.setBox(selection.style().config().labels().box());
        applyLabelText(current.hologram, label);
        current.hologram.setParticleLayers(selection.style().config().particleLayers());
    }

    static void applyLabelText(TemporaryHologram hologram, RealDropService.Label label) {
        hologram.bindRenderedViewerText(null);
        if (label.renderer() != null) {
            hologram.setRenderedLines(label.lines());
            if (label.personalized()) {
                hologram.bindRenderedViewerText(label.renderer());
            } else {
                TemporaryHologram.RenderedText text = label.renderer().apply(null);
                hologram.setRenderedParticleText(text.text(), text.spans());
            }
            return;
        }
        if (label.authoredLines().isEmpty()) {
            hologram.setRenderedLines(label.lines());
        } else {
            hologram.setLines(label.authoredLines());
        }
    }

    private static TemporaryHologram.RenderedText renderedText(ParticleText.Rendered rendered) {
        List<ParticleTextSpan> spans = new ArrayList<>(rendered.spans().size());
        for (ParticleText.Span span : rendered.spans()) {
            spans.add(new ParticleTextSpan(span.name(), span.start(), span.end()));
        }
        return new TemporaryHologram.RenderedText(rendered.text(), List.copyOf(spans), null);
    }

    static ParticleText.Rendered particleText(TemporaryHologram.RenderedText rendered) {
        List<ParticleText.Span> spans = new ArrayList<>(rendered.spans().size());
        for (ParticleTextSpan span : rendered.spans()) {
            spans.add(new ParticleText.Span(span.name(), span.start(), span.end()));
        }
        return new ParticleText.Rendered(rendered.text(), List.copyOf(spans));
    }

    private void removeConditionalLabel(UUID itemId) {
        ConditionalLabel label = conditionalLabels.remove(itemId);
        if (label != null) {
            label.hologram.destroy();
        }
    }

    private void clearConditionalLabels() {
        for (UUID itemId : conditionalLabels.keySet()) {
            removeConditionalLabel(itemId);
        }
    }

    private List<String> verticalLabelLines(List<DropNameFormatter.BundleContent> contents,
                                            BundleFormats suppliedFormats,
                                            GlossConfig.RealDrops.LabelBundle bundle, String fallback,
                                            UnaryOperator<String> name) {
        if (contents.isEmpty() || !bundle.vertical()) {
            return List.of(fallback);
        }
        BundleFormats formats = suppliedFormats == null
            ? new BundleFormats(bundle.headerFormat(), bundle.entryFormat(), bundle.moreFormat(), bundle.entryLimit())
            : suppliedFormats;
        return DropNameFormatter.formatBundleLines(
            formats.header(), formats.entry(), formats.more(), contents, formats.entryLimit(), name);
    }

    static String renderNativeName(String authored, String fallback, UnaryOperator<String> renderer) {
        return TextPipeline.viewerSpecific(authored) ? fallback : renderer.apply(authored);
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
        refreshOnOwner(item, null, eventType);
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
        if (plugin.cfg().drops().enabled() || plugin.cfg().realDrops().enabled() || plugin.cfg().particles().enabled()) {
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

    public void refreshNames() {
        renderedNames.clear();
        reloadPresentations();
    }

    private void refreshRealDropConfig() {
        realDropPlan = compileRealDropPlan(realDropDoc, plugin.cfg().realDrops().enabled());
    }

    private void reloadPresentations() {
        if (!listening) {
            return;
        }
        cancelRehydration();
        clearConditionalLabels();
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

    private LabelPresentation buildLabel(ItemStack stack, int count, BundleFormats formats,
                                          GlossConfig.RealDrops.Labels labels) {
        DropItemName name = DropItemName.capture(stack, labels);
        List<NamedContent> contents = bundleContents(stack, labels);
        boolean literal = name.literal();
        for (NamedContent content : contents) {
            literal |= content.name().literal();
        }
        LabelInput input = new LabelInput(name, count, contents, formats, labels);
        if (literal) {
            TemporaryHologram.RenderedText shared = renderNamedLabel(input, null, false);
            boolean personalized = personalized(input);
            Function<Player, TemporaryHologram.RenderedText> renderer = personalized
                ? viewer -> renderNamedLabel(input, viewer, false) : viewer -> shared;
            return new LabelPresentation(renderNamedLabel(input, null, true).text(),
                new RealDropService.Label(List.of(), List.of(shared.text().split("\\n", -1)), renderer, personalized));
        }
        List<DropNameFormatter.BundleContent> materialContents = resolvedContents(contents, null, null);
        String raw = rawLabel(input, materialContents, name.fallback(), UnaryOperator.identity());
        String fallback = count + "x " + name.fallback();
        List<String> authored = verticalLabelLines(materialContents, formats, labels.bundle(), raw, UnaryOperator.identity());
        List<String> rendered = new ArrayList<>(authored.size());
        for (String line : authored) {
            rendered.add(renderNativeName(line, fallback, this::renderName));
        }
        return new LabelPresentation(renderNativeName(raw, fallback, this::renderName),
            new RealDropService.Label(authored, rendered, null, false));
    }

    private static boolean personalized(LabelInput input) {
        if (input.name().viewerName() != null || TextPipeline.viewerDependent(input.labels().format())
            || !input.name().literal() && TextPipeline.viewerDependent(input.name().fallback())) {
            return true;
        }
        if (input.contents().isEmpty()) {
            return false;
        }
        GlossConfig.RealDrops.LabelBundle bundle = input.labels().bundle();
        if (TextPipeline.viewerDependent(bundle.format()) || TextPipeline.viewerDependent(bundle.headerFormat())
            || TextPipeline.viewerDependent(bundle.entryFormat()) || TextPipeline.viewerDependent(bundle.moreFormat())) {
            return true;
        }
        if (input.formats() != null && (TextPipeline.viewerDependent(input.formats().header())
            || TextPipeline.viewerDependent(input.formats().entry())
            || TextPipeline.viewerDependent(input.formats().more()))) {
            return true;
        }
        for (NamedContent content : input.contents()) {
            if (content.name().viewerName() != null
                || !content.name().literal() && TextPipeline.viewerDependent(content.name().fallback())) {
                return true;
            }
        }
        return false;
    }

    private TemporaryHologram.RenderedText renderNamedLabel(LabelInput input, Player viewer, boolean nativeName) {
        DropLabelTemplate template = new DropLabelTemplate();
        String resolved = input.name().resolve(viewer);
        List<DropNameFormatter.BundleContent> contents = resolvedContents(input.contents(), viewer, template);
        String type = input.name().literal() ? template.literal(resolved) : resolved;
        String raw = rawLabel(input, contents, type, template::name);
        String authored = nativeName ? raw : String.join("\n",
            verticalLabelLines(contents, input.formats(), input.labels().bundle(), raw, template::name));
        if (viewer == null && TextPipeline.viewerSpecific(authored)) {
            return new TemporaryHologram.RenderedText(input.count() + "x " + resolved, List.of(), null);
        }
        if (!nativeName && plugin.animator() != null) {
            AnimationTemplate animation = plugin.animator().compileTemplate(
                List.of(ParticleText.parse(authored).marked()), source -> plugin.text().render(viewer, source));
            if (animation != null) {
                DropLabelTemplate.Frames frames = template.frames(animation);
                ParticleText.Rendered current = frames.render(System.currentTimeMillis());
                TemporaryHologram.RenderedText text = renderedText(current);
                return new TemporaryHologram.RenderedText(text.text(), text.spans(), frames);
            }
        }
        return renderedText(template.render(authored,
            source -> plugin.text().renderLegacyParticleText(viewer, source)));
    }

    private static String rawLabel(LabelInput input, List<DropNameFormatter.BundleContent> contents,
                                   String type, UnaryOperator<String> name) {
        return contents.isEmpty()
            ? DropNameFormatter.format(input.labels().format(), input.count(), type)
            : DropNameFormatter.formatBundle(input.labels().bundle().format(), contents,
                bundleEntryLimit(input.formats(), input.labels().bundle()), DropNameService::renderMore, name);
    }

    private static List<DropNameFormatter.BundleContent> resolvedContents(List<NamedContent> contents,
                                                                          Player viewer,
                                                                          DropLabelTemplate template) {
        List<DropNameFormatter.BundleContent> resolved = new ArrayList<>(contents.size());
        for (NamedContent content : contents) {
            String name = content.name().resolve(viewer);
            resolved.add(new DropNameFormatter.BundleContent(
                content.name().literal() && template != null ? template.literal(name) : name, content.count()));
        }
        return resolved;
    }

    private static List<NamedContent> bundleContents(ItemStack stack, GlossConfig.RealDrops.Labels labels) {
        if (stack.getType() != Material.BUNDLE || !(stack.getItemMeta() instanceof BundleMeta meta)) {
            return List.of();
        }

        List<ItemStack> items = meta.getItems();
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        List<NamedContent> contents = new ArrayList<>(items.size());
        for (ItemStack carried : items) {
            if (carried != null) {
                contents.add(new NamedContent(DropItemName.capture(carried, labels), carried.getAmount()));
            }
        }
        return contents;
    }

    private static int bundleEntryLimit(BundleFormats formats, GlossConfig.RealDrops.LabelBundle bundle) {
        return formats == null ? bundle.entryLimit() : formats.entryLimit();
    }

    private void clearNameOwnership(Item item) {
        item.getPersistentDataContainer().remove(nameKey);
        item.getPersistentDataContainer().remove(renderedNameKey);
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
            item, selection, label.authoredText(), label.text(), label.renderer(), label.personalized()));
    }

    private void driveNativeParticles() {
        if (!listening || nativeParticleLabels.isEmpty() || !plugin.cfg().particles().enabled()) {
            return;
        }
        long tick = System.currentTimeMillis() / 50L;
        for (NativeParticleLabel state : nativeParticleLabels.values()) {
            if (state.selection().style().config().particleLayers().isEmpty()) {
                continue;
            }
            Item item = state.item();
            FoliaScheduler.runEntity(plugin, item, () -> emitNativeParticlesOwned(state, tick), 0L,
                () -> nativeParticleLabels.remove(item.getUniqueId(), state));
        }
    }

    private void emitNativeParticlesOwned(NativeParticleLabel state, long tick) {
        Item item = state.item();
        if (!listening || !item.isValid() || item.isDead()
            || nativeParticleLabels.get(item.getUniqueId()) != state) {
            nativeParticleLabels.remove(item.getUniqueId(), state);
            return;
        }
        GlossConfig.RealDrops config = state.selection().style().config();
        if (!config.labels().show().isAlwaysVisible()) {
            return;
        }
        double range = 0.0D;
        for (ParticleLayer layer : config.particleLayers()) {
            range = Math.max(range, layer.viewDistance());
        }
        Location origin = item.getLocation().clone().add(0.0D, config.labels().yOffset(), 0.0D);
        boolean viewerText = state.personalized() || TextPipeline.viewerSpecific(state.authored());
        AtomicReference<NativeParticleFrame> shared = new AtomicReference<>();
        plugin.holograms().forEachNearbyViewer(origin, range * range,
            viewer -> plugin.scheduler().runEntity(viewer,
                () -> emitNativeParticlesForViewer(state, viewer, origin, tick, viewerText, shared)));
    }

    /**
     * A label with no viewer-specific token renders and lays out the same for everyone, so the first
     * viewer of an emitting tick builds it and the rest of that tick reuses it.
     */
    private NativeParticleFrame sharedNativeLabel(NativeParticleLabel state,
                                                  AtomicReference<NativeParticleFrame> shared) {
        NativeParticleFrame cached = shared.get();
        if (cached != null) {
            return cached;
        }
        ParticleText.Rendered rendered = state.renderer() != null ? particleText(state.renderer().apply(null)) : state.authored().isEmpty()
            ? new ParticleText.Rendered(state.rendered(), List.of())
            : plugin.text().renderLegacyParticleText(null, state.authored());
        List<ParticleLayer> layers = state.selection().style().config().particleLayers();
        List<List<ParticleRect>> targets = new ArrayList<>(layers.size());
        for (ParticleLayer layer : layers) {
            targets.add(nativeLabelTargets(layer, rendered));
        }
        cached = new NativeParticleFrame(rendered, List.copyOf(targets));
        shared.set(cached);
        return cached;
    }

    private void emitNativeParticlesForViewer(NativeParticleLabel state, Player viewer,
                                               Location origin, long tick, boolean viewerText,
                                               AtomicReference<NativeParticleFrame> sharedLabel) {
        if (!viewer.isOnline() || !state.selection().visibleTo(plugin, viewer)) {
            return;
        }
        List<ParticleLayer> layers = state.selection().style().config().particleLayers();
        boolean due = false;
        for (ParticleLayer layer : layers) {
            if (plugin.particles().isDue(viewer, state.item(), layer, tick)) {
                due = true;
                break;
            }
        }
        if (!due) {
            return;
        }
        NativeParticleFrame shared = viewerText ? null : sharedNativeLabel(state, sharedLabel);
        ParticleText.Rendered rendered = shared == null
            ? state.renderer() == null
                ? plugin.text().renderLegacyParticleText(viewer, state.authored())
                : particleText(state.renderer().apply(viewer))
            : shared.rendered();
        ParticleFrame frame = nativeLabelFrame(viewer, origin);
        for (int index = 0; index < layers.size(); index++) {
            ParticleLayer layer = layers.get(index);
            if (!plugin.particles().isDue(viewer, state.item(), layer, tick)) {
                continue;
            }
            List<ParticleRect> targets = shared == null
                ? nativeLabelTargets(layer, rendered)
                : shared.targets().get(index);
            if (!layer.target().scope().equals("local") && targets.isEmpty()) {
                continue;
            }
            plugin.particles().emit(viewer, state.item(), frame, layer, targets, tick);
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
        removeConditionalLabel(entityId);
    }

    private static final class ConditionalLabel {
        private final TemporaryHologram hologram;
        private volatile RealDropConditionPlan.Selection selection;

        private ConditionalLabel(TemporaryHologram hologram, RealDropConditionPlan.Selection selection) {
            this.hologram = hologram;
            this.selection = selection;
        }
    }

    private record BundleFormats(String header, String entry, String more, int entryLimit) {
    }

    private record NamedContent(DropItemName name, int count) {
    }

    private record LabelInput(DropItemName name, int count, List<NamedContent> contents,
                               BundleFormats formats, GlossConfig.RealDrops.Labels labels) {
    }

    private record LabelPresentation(String nativeName, RealDropService.Label label) {
    }

    private record LoadedChunk(World world, int chunkX, int chunkZ) {
    }

    private record NativeParticleLabel(Item item, RealDropConditionPlan.Selection selection,
                                       String authored, String rendered,
                                       Function<Player, TemporaryHologram.RenderedText> renderer, boolean personalized) {
    }

    private record NativeParticleFrame(ParticleText.Rendered rendered, List<List<ParticleRect>> targets) {
    }

    @Override
    public Map<String, DocumentRegistry<?>> registries() {
        return Map.of("real-drops", realDropSettings);
    }
}
