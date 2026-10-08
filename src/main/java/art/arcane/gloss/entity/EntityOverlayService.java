package art.arcane.gloss.entity;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.service.VisibilityGovernor;
import art.arcane.gloss.integration.EcoMobsEntityNames;
import art.arcane.gloss.bedrock.BedrockPolicy;
import art.arcane.gloss.bedrock.BedrockSurface;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.api.ParticleTextSpan;
import art.arcane.gloss.particle.ParticleText;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.gloss.nametag.NametagService;
import art.arcane.gloss.nameplate.NameplateSuppression;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.RegistryOwner;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.condition.RoleSnapshotStore;
import art.arcane.gloss.condition.EntityRoleSnapshotRuntime;
import art.arcane.gloss.condition.EntityRelationshipSnapshot;
import art.arcane.gloss.condition.RoleSnapshotScope;
import art.arcane.gloss.condition.RoleSnapshotPendingException;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.expr.ExprRoleSnapshot;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class EntityOverlayService implements Listener, RegistryOwner {
    private static final NamespacedKey REACT_STACK_COUNT = new NamespacedKey("react", "react-stack-count");
    private static final long ANCHOR_GRACE_DRIVES = 1L;
    private static final long AUDIENCE_GRACE_DRIVES = 1L;

    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<EntityOverlayDoc> registry;
    private final NameplateSuppression suppression;
    private final RoleSnapshotStore roleSnapshots;
    private final ConcurrentMap<UUID, EntityOverlayCell.Anchor> anchors = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, EntityOverlayTarget> overlays = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Insight> insights = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Set<UUID>> insightTargets = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Integer> stackCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Hit> hits = new ConcurrentHashMap<>();
    private final Set<Plugin> restrictions = ConcurrentHashMap.newKeySet();
    private final Set<EntityOverlaySource> sources = ConcurrentHashMap.newKeySet();
    private final AtomicLong renderPasses = new AtomicLong();
    private final AtomicLong textPreparations = new AtomicLong();
    private final AtomicLong removalMutations = new AtomicLong();
    private final AtomicLong driveSequence = new AtomicLong();
    private volatile EntityOverlayDoc settings;
    private volatile List<PresentationVariant> presentationVariants = List.of();

    private record PresentationVariant(ShowCondition condition, EntityOverlayDoc document) {}
    private volatile boolean started;
    private volatile boolean reactPresent;
    private volatile boolean ecoMobsPresent;
    private volatile boolean refreshText;
    private volatile boolean trackDistance;
    private volatile boolean personalText;
    private volatile long renderGeneration = -1;
    private volatile long emojiGeneration = -1;
    private volatile long animationGeneration = -1;
    private int taskId = -1;
    private volatile int driverIntervalTicks;
    private final AtomicLong driveTicks = new AtomicLong();

    public EntityOverlayService(Gloss plugin) {
        this.plugin = plugin;
        roleSnapshots = new RoleSnapshotStore(new EntityRoleSnapshotRuntime(plugin, () -> started),
            () -> settings == null ? 4096 : settings.snapshotReadLimit());
        suppression = new NameplateSuppression(plugin.teams(), "entity-overlay");
        File folder = new File(plugin.getDataFolder(), EntityOverlayDoc.KIND);
        defaults = new ShippedDefaults(EntityOverlayDoc.KIND, folder,
            ShippedDocumentCatalog.ENTITY_OVERLAYS.names());
        registry = DocumentRegistry.folder(EntityOverlayDoc.KIND, folder, EntityOverlayDoc::parse,
            EntityOverlayDoc::revision);
    }

    public void enable() {
        started = true;
        reactPresent = plugin.getServer().getPluginManager().isPluginEnabled("React");
        ecoMobsPresent = plugin.getServer().getPluginManager().isPluginEnabled("EcoMobs");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        reload();
        plugin.watchdog().register(EntityOverlayDoc.KIND, this::poll);
    }

    public void disable() {
        started = false;
        ecoMobsPresent = false;
        plugin.watchdog().unregister(EntityOverlayDoc.KIND);
        HandlerList.unregisterAll(this);
        stopDriver();
        registry.close();
        restrictions.clear();
        insights.clear();
        insightTargets.clear();
        stackCounts.clear();
        hits.clear();
    }

    public void reload() {
        defaults.extractMissing();
        registry.reload();
        GlossDocument<EntityOverlayDoc> document = registry.get(EntityOverlayDoc.DEFAULT_ID);
        applySettings(document == null ? null : document.value());
    }

    public boolean enabled() {
        EntityOverlayDoc current = settings;
        return started && current != null && current.enabled() && plugin.cfg().holograms().enabled();
    }

    public List<String> resetToDefault(String name) {
        List<String> restored = defaults.resetToDefault(name);
        if (!restored.isEmpty()) {
            registry.reload();
            GlossDocument<EntityOverlayDoc> document = registry.get(EntityOverlayDoc.DEFAULT_ID);
            applySettings(document == null ? null : document.value());
        }
        return restored;
    }

    public int activeCount() {
        return overlays.size();
    }

    public boolean refreshStack(LivingEntity entity, int count) {
        Objects.requireNonNull(entity, "entity");
        if (count <= 1) {
            stackCounts.remove(entity.getUniqueId());
        } else {
            stackCounts.put(entity.getUniqueId(), count);
        }
        return enabled();
    }

    public void removeStack(LivingEntity entity) {
        stackCounts.remove(Objects.requireNonNull(entity, "entity").getUniqueId());
    }

    public boolean updateInsight(Plugin owner, Player viewer, LivingEntity target,
                                 List<String> details, long durationMs) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(target, "target");
        List<String> lines = List.copyOf(details);
        if (lines.size() > 16 || lines.stream().anyMatch(line -> line.length() > 1024)) {
            throw new IllegalArgumentException("Entity insight exceeds 16 lines or 1024 characters per line");
        }
        if (!enabled() || !owner.isEnabled()) {
            return false;
        }
        Insight next = new Insight(owner, target, target.getUniqueId(), lines,
            System.currentTimeMillis() + Math.clamp(durationMs, 100, 10000));
        linkInsight(viewer.getUniqueId(), next);
        return true;
    }

    public void clearInsight(Plugin owner, UUID viewerId) {
        insights.computeIfPresent(viewerId, (id, current) -> {
            if (current.owner() != owner) {
                return current;
            }
            unlinkInsight(id, current);
            return null;
        });
    }

    /**
     * Adds a second owner of panes. While any source is registered, players are eligible even when
     * the overlay document turns {@code includePlayers} off, which is how nameplates take players
     * over without two panes ending up above one head.
     */
    public void registerSource(EntityOverlaySource source) {
        sources.add(Objects.requireNonNull(source, "source"));
        refreshSources();
    }

    public void unregisterSource(EntityOverlaySource source) {
        sources.remove(source);
        refreshSources();
    }

    public void refreshSources() {
        EntityOverlayDoc current = settings;
        if (!enabled() || current == null) {
            return;
        }
        int interval = current.updateIntervalTicks();
        for (EntityOverlaySource source : sources) {
            int requested = source.scanPolicy().intervalTicks();
            if (requested > 0) {
                interval = Math.min(interval, requested);
            }
        }
        for (EntityOverlayTarget overlay : overlays.values()) {
            overlay.dirty = true;
            overlay.nextRenderTick = 0;
        }
        if (interval == driverIntervalTicks && taskId != -1) {
            return;
        }
        if (taskId != -1) {
            plugin.scheduler().csr(taskId);
        }
        driverIntervalTicks = interval;
        taskId = plugin.scheduler().sr(this::drive, interval);
    }

    private double scanRange(EntityOverlayDoc current) {
        double range = current.range();
        for (EntityOverlaySource source : sources) {
            range = Math.max(range, source.scanPolicy().range());
        }
        return range;
    }

    /** True while another lane owns player panes. */
    public boolean playersOwnedElsewhere() {
        return !sources.isEmpty();
    }

    public void restrict(Plugin owner, boolean restricted) {
        Objects.requireNonNull(owner, "owner");
        if (restricted && owner.isEnabled()) {
            restrictions.add(owner);
        } else {
            restrictions.remove(owner);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        EntityOverlayDoc current = settings;
        if (current == null || !enabled()
            || !(event.getEntity() instanceof LivingEntity entity) || event.getFinalDamage() <= 0) {
            return;
        }
        hits.put(entity.getUniqueId(), new Hit(entity.getHealth(), event.getFinalDamage(),
            System.currentTimeMillis() + current.hitHighlightMs()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        removeEntity(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent event) {
        if (event.getEntity() instanceof LivingEntity) {
            removeEntity(event.getEntity().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof LivingEntity) {
                removeEntity(entity.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        removeViewer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        removeViewer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        Plugin owner = event.getPlugin();
        restrictions.remove(owner);
        for (Map.Entry<UUID, Insight> entry : insights.entrySet()) {
            if (entry.getValue().owner() == owner) {
                clearInsight(owner, entry.getKey());
            }
        }
        if (owner.getName().equals("React")) {
            reactPresent = false;
            stackCounts.clear();
        }
        if (owner.getName().equals("EcoMobs")) {
            ecoMobsPresent = false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("React")) {
            reactPresent = true;
        }
        if (event.getPlugin().getName().equals("EcoMobs")) {
            ecoMobsPresent = true;
        }
    }

    private void poll() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        GlossDocument<EntityOverlayDoc> document = registry.get(delta, EntityOverlayDoc.DEFAULT_ID);
        EntityOverlayDoc updated = document == null ? null : document.value();
        if (!registry.dispatch(delta, task -> SchedulerUtils.runGlobal(plugin, task),
            () -> applySettings(updated))) {
            Gloss.warnThrottled("entity-overlay-hotload-scheduling",
                "Entity overlay hot reload could not reach the server thread; the change will be retried.");
        }
    }

    private void applySettings(EntityOverlayDoc updated) {
        stopDriver();
        settings = updated;
        List<PresentationVariant> variants = new ArrayList<>();
        if (updated != null) {
            for (EntityOverlayDoc.Variant variant : updated.variants()) {
                variants.add(new PresentationVariant(ShowCondition.of(variant.when()), variant.presentation().apply(updated)));
            }
        }
        presentationVariants = List.copyOf(variants);
        refreshText = updated != null && EntityOverlayText.refreshRequired(updated);
        trackDistance = updated != null && EntityOverlayText.usesDistance(updated);
        personalText = updated != null && EntityOverlayText.personalRequired(updated,
            plugin.animations()::framesViewerSpecific);
        refreshSources();
    }

    private void stopDriver() {
        if (taskId != -1) {
            plugin.scheduler().csr(taskId);
            taskId = -1;
        }
        driverIntervalTicks = 0;
        roleSnapshots.clear();
        anchors.clear();
        insights.clear();
        insightTargets.clear();
        for (UUID targetId : List.copyOf(overlays.keySet())) {
            EntityOverlayTarget overlay = overlays.remove(targetId);
            if (overlay != null) {
                destroy(overlay);
            }
        }
        suppression.clear();
    }

    private void drive() {
        EntityOverlayDoc current = settings;
        if (!enabled() || current == null) {
            return;
        }
        long sequence = driveSequence.incrementAndGet();
        driveTicks.addAndGet(driverIntervalTicks);
        long now = System.currentTimeMillis();
        sweepHits(now);
        sweepInsights(now);
        markStaleRenders();
        sweepOverlays(sequence);
        sampleAnchors(current, sequence);
        admitInsightTargets(current, sequence);
        scanCells(current, sequence);
    }

    private void sweepHits(long now) {
        hits.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    private void sweepInsights(long now) {
        for (Map.Entry<UUID, Insight> entry : insights.entrySet()) {
            if (entry.getValue().expiresAt() > now) {
                continue;
            }
            insights.computeIfPresent(entry.getKey(), (viewerId, current) -> {
                if (current.expiresAt() > now) {
                    return current;
                }
                unlinkInsight(viewerId, current);
                return null;
            });
        }
    }

    private void markStaleRenders() {
        long render = plugin.text().renderGeneration();
        long emoji = TextPipeline.emojiGeneration();
        long animation = plugin.animations().generation();
        boolean drift = render != renderGeneration || emoji != emojiGeneration
            || animation != animationGeneration;
        if (drift) {
            if (animation != animationGeneration) {
                EntityOverlayDoc current = settings;
                personalText = current != null && EntityOverlayText.personalRequired(current,
                    plugin.animations()::framesViewerSpecific);
            }
            renderGeneration = render;
            emojiGeneration = emoji;
            animationGeneration = animation;
        }
        boolean refreshPlayers = plugin.cfg().modules().nametags().enabled()
            || plugin.cfg().modules().nameplates().enabled();
        boolean refreshAll = drift || refreshText || trackDistance;
        if (!refreshAll && !refreshPlayers) {
            return;
        }
        for (EntityOverlayTarget overlay : overlays.values()) {
            if (refreshAll || overlay.target instanceof Player) {
                overlay.dirty = true;
            }
        }
    }

    private void sweepOverlays(long sequence) {
        for (EntityOverlayTarget overlay : overlays.values()) {
            List<UUID> dropped = new ArrayList<>();
            for (Map.Entry<UUID, Long> entry : overlay.audience.entrySet()) {
                if (sequence - entry.getValue() > AUDIENCE_GRACE_DRIVES) {
                    dropped.add(entry.getKey());
                }
            }
            overlay.audience.keySet().removeAll(dropped);
            retired(overlay, dropped);
            if (overlay.audience.isEmpty()) {
                if (overlays.remove(overlay.targetId(), overlay)) {
                    destroy(overlay);
                }
                continue;
            }
            if (!dropped.isEmpty()) {
                overlay.personalViewers.removeIf(viewerId -> !overlay.audience.containsKey(viewerId));
                overlay.dirty = true;
            }
        }
    }

    /** Tears an overlay down and tells the sources every pair they were holding state for is gone. */
    private void destroy(EntityOverlayTarget overlay) {
        List<UUID> audience = List.copyOf(overlay.audience.keySet());
        overlay.destroy();
        overlays.compute(overlay.targetId(), (targetId, active) -> {
            if (active == null || active == overlay) {
                roleSnapshots.forget(targetId);
            }
            return active;
        });
        retired(overlay, audience);
    }

    /**
     * A pane stopped being drawn for these viewers. Sources keep per-pair state outside the pane -
     * nameplate suppression holds a scoreboard team that hides the vanilla tag - and never hear
     * about the subject walking out of range unless they are told here.
     */
    private void retired(EntityOverlayTarget overlay, List<UUID> viewerIds) {
        if (viewerIds.isEmpty()) {
            return;
        }
        overlays.compute(overlay.targetId(), (targetId, active) -> {
            if (active != null && active != overlay) {
                return active;
            }
            for (UUID viewerId : viewerIds) {
                suppression.retire(viewerId, targetId);
                for (EntityOverlaySource source : sources) {
                    source.retired(viewerId, targetId);
                }
            }
            return active;
        });
    }

    private void sampleAnchors(EntityOverlayDoc current, long sequence) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID viewerId = player.getUniqueId();
            Runnable retired = () -> dropViewer(viewerId, sequence);
            if (!FoliaScheduler.runEntity(plugin, player,
                () -> captureAnchor(player, viewerId, current, sequence), 0, retired)) {
                retired.run();
            }
        }
        anchors.entrySet().removeIf(entry -> sequence - entry.getValue().sequence() > AUDIENCE_GRACE_DRIVES);
    }

    private void captureAnchor(Player player, UUID viewerId, EntityOverlayDoc current, long sequence) {
        if (!enabled() || settings != current) {
            return;
        }
        if (!player.isOnline() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR
            || current.blacklistWorlds().contains(player.getWorld().getName())) {
            dropViewer(viewerId, sequence);
            return;
        }
        Location at = player.getLocation();
        World world = at.getWorld();
        if (world == null) {
            dropViewer(viewerId, sequence);
            return;
        }
        anchors.put(viewerId, new EntityOverlayCell.Anchor(viewerId, player, world,
            at.getX(), at.getY(), at.getZ(), sequence));
    }

    private void scanCells(EntityOverlayDoc current, long sequence) {
        if (!restrictions.isEmpty()) {
            return;
        }
        for (EntityOverlayCell cell : EntityOverlayCell.bucket(anchors.values(), sequence, ANCHOR_GRACE_DRIVES)) {
            Location center = cell.center();
            if (!plugin.scheduler().runAt(center, () -> scanCell(cell, current, sequence))) {
                Gloss.warnThrottled("entity-overlay-cell-scheduling",
                    "Entity overlay scan could not reach a region thread; the pass was skipped.");
            }
        }
    }

    private void scanCell(EntityOverlayCell cell, EntityOverlayDoc current, long sequence) {
        if (!enabled() || settings != current) {
            return;
        }
        try {
            BoundingBox box = cell.box(scanRange(current));
            if (!EntityOverlayCell.owned(cell.world(), box, folia())) {
                splitCell(cell, current, sequence);
                return;
            }
            List<CellTarget> targets = sampleTargets(cell.world(), box, current);
            if (targets.isEmpty()) {
                return;
            }
            for (int index = 0; index < cell.size(); index++) {
                admitViewer(cell.anchor(index), targets, current, sequence);
            }
        } catch (RuntimeException failure) {
            Gloss.logExceptionStackThrottled(false, "entity-overlay-scan", failure,
                "Failed to scan entity overlays around %s.", cell.world().getName());
        }
    }

    private boolean folia() {
        return plugin.scheduler().isFoliaThreading();
    }

    private void splitCell(EntityOverlayCell cell, EntityOverlayDoc current, long sequence) {
        for (int index = 0; index < cell.size(); index++) {
            EntityOverlayCell.Anchor anchor = cell.anchor(index);
            if (!plugin.scheduler().runAt(anchor.location(), () -> scanAnchor(anchor, current, sequence))) {
                Gloss.warnThrottled("entity-overlay-cell-scheduling",
                    "Entity overlay scan could not reach a region thread; the pass was skipped.");
            }
        }
    }

    private void scanAnchor(EntityOverlayCell.Anchor anchor, EntityOverlayDoc current, long sequence) {
        if (!enabled() || settings != current) {
            return;
        }
        try {
            BoundingBox box = EntityOverlayCell.ownedPortion(anchor.world(),
                anchor.box(scanRange(current)), anchor.x(), anchor.z(), folia());
            if (box == null) {
                return;
            }
            List<CellTarget> targets = sampleTargets(anchor.world(), box, current);
            if (targets.isEmpty()) {
                return;
            }
            admitViewer(anchor, targets, current, sequence);
        } catch (RuntimeException failure) {
            Gloss.logExceptionStackThrottled(false, "entity-overlay-scan", failure,
                "Failed to scan entity overlays around %s.", anchor.world().getName());
        }
    }

    private List<CellTarget> sampleTargets(World world, BoundingBox box, EntityOverlayDoc current) {
        List<CellTarget> targets = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Entity candidate : world.getNearbyEntities(box, EntityOverlayService::isLivingCandidate)) {
            LivingEntity target = (LivingEntity) candidate;
            EntityRelationshipSnapshot relationship = EntityRelationshipSnapshot.captureOnOwner(target);
            EntityOverlaySource source = sourceFor(relationship);
            if (!eligible(target, current, relationship, source)) {
                continue;
            }
            if (roleSnapshots.isTracked(target.getUniqueId())) {
                roleSnapshots.captureOnOwner(target, Set.of());
            }
            Location position = target.getLocation();
            Hit hit = hits.get(target.getUniqueId());
            boolean struck = hit != null && hit.expiresAt() > now;
            double health = target.getHealth();
            EntityOverlayText.Snapshot snapshot = new EntityOverlayText.Snapshot(
                entityName(target), health, attribute(target, Attribute.MAX_HEALTH),
                struck ? hit.previousHealth() : health, struck ? hit.damage() : 0,
                attribute(target, Attribute.ATTACK_DAMAGE), attribute(target, Attribute.ARMOR),
                stackCount(target), target.getType().getKey().getKey(), 0);
            targets.add(new CellTarget(target, target.getUniqueId(), position.getX(), position.getY(),
                position.getZ(), snapshot,
                position.clone().add(0, target.getHeight() + current.verticalOffset(), 0), relationship, source));
        }
        return targets;
    }

    private void admitViewer(EntityOverlayCell.Anchor anchor, List<CellTarget> targets,
                             EntityOverlayDoc current, long sequence) {
        Player viewer = anchor.player();
        Runnable admit = () -> admitOnViewerRegion(viewer, anchor, targets, current, sequence);
        if (!FoliaScheduler.runEntity(plugin, viewer, admit, 0, null)) {
            dropViewer(anchor.viewerId(), sequence);
        }
    }

    private void admitOnViewerRegion(Player viewer, EntityOverlayCell.Anchor anchor, List<CellTarget> targets,
                                     EntityOverlayDoc current, long sequence) {
        if (!enabled() || settings != current || !viewer.isOnline()) {
            return;
        }
        BedrockPolicy policy = BedrockPolicy.of(plugin);
        if (policy != null && policy.hides(BedrockSurface.OVERLAY, viewer)) {
            return;
        }
        UUID viewerId = anchor.viewerId();
        Comparator<Admission> nearest = Comparator.comparingDouble(Admission::distanceSquared)
            .thenComparing(admission -> admission.target().targetId());
        Map<EntityOverlaySource, PriorityQueue<Admission>> groups = new HashMap<>();
        for (CellTarget target : targets) {
            EntityOverlaySource source = target.source();
            if (source != null && !sources.contains(source)) {
                continue;
            }
            if (viewerId.equals(target.targetId()) && (source == null || !source.includesSelf())) {
                continue;
            }
            EntityOverlaySource.ScanPolicy sourcePolicy = source == null ? EntityOverlaySource.ScanPolicy.INHERIT : source.scanPolicy();
            double range = sourcePolicy.range() > 0 ? sourcePolicy.range() : current.range();
            double distanceSquared = anchor.distanceSquared(target.x(), target.y(), target.z());
            if (distanceSquared > range * range || !viewer.canSee(target.entity())) {
                continue;
            }
            int maximum = sourcePolicy.subjects() > 0 ? sourcePolicy.subjects() : current.maxEntitiesPerViewer();
            EntityOverlaySource group = sourcePolicy.subjects() > 0 ? source : null;
            PriorityQueue<Admission> selected = groups.get(group);
            if (selected == null) {
                selected = new PriorityQueue<>(maximum, nearest.reversed());
                groups.put(group, selected);
            }
            Admission admission = new Admission(target, distanceSquared);
            if (selected.size() < maximum) {
                selected.add(admission);
            }
            else if (nearest.compare(admission, selected.peek()) < 0) {
                selected.poll();
                selected.add(admission);
            }
        }
        List<Admission> admissions = new ArrayList<>();
        for (PriorityQueue<Admission> group : groups.values()) {
            admissions.addAll(group);
        }
        admissions.sort(nearest);
        for (Admission admission : admissions) {
            EntityOverlayTarget overlay = admit(viewerId, admission.target(), current, sequence);
            if (overlay != null && overlay.dirty) {
                dispatchRender(overlay, viewer, current);
            }
        }
    }

    private EntityOverlayTarget admit(UUID viewerId, CellTarget target, EntityOverlayDoc current, long sequence) {
        EntityOverlayTarget overlay = overlays.get(target.targetId());
        if (overlay == null) {
            if (overlays.size() >= current.maxActiveOverlays()) {
                return null;
            }
            overlay = overlays.computeIfAbsent(target.targetId(), EntityOverlayTarget::new);
        }
        overlay.target = target.entity();
        overlay.relationship = target.relationship();
        overlay.source = target.source();
        overlay.publish(target.snapshot(), target.anchor(), target.x(), target.y(), target.z());
        NametagService nametags = plugin.service(NametagService.class);
        boolean personal = personalText || target.source() != null
            || target.entity() instanceof Player && nametags != null && nametags.viewerDependent()
            || insightFor(viewerId, target.targetId()) != null;
        overlay.audience.put(viewerId, sequence);
        if (personal ? overlay.personalViewers.add(viewerId) : overlay.personalViewers.remove(viewerId)) {
            overlay.dirty = true;
        }
        if (overlay.awaiting(viewerId, personal)) {
            overlay.dirty = true;
        }
        return overlay;
    }

    private void admitInsightTargets(EntityOverlayDoc current, long sequence) {
        for (Map.Entry<UUID, Insight> entry : insights.entrySet()) {
            UUID viewerId = entry.getKey();
            Insight insight = entry.getValue();
            if (!anchors.containsKey(viewerId)) {
                continue;
            }
            LivingEntity target = insight.target();
            if (!FoliaScheduler.runEntity(plugin, target,
                () -> admitInsightTarget(viewerId, insight, current, sequence), 0, null)) {
                dropInsight(viewerId, insight);
            }
        }
    }

    private void admitInsightTarget(UUID viewerId, Insight insight, EntityOverlayDoc current, long sequence) {
        if (!enabled() || settings != current || insights.get(viewerId) != insight) {
            return;
        }
        EntityOverlayCell.Anchor anchor = anchors.get(viewerId);
        LivingEntity target = insight.target();
        if (anchor == null) {
            return;
        }
        EntityRelationshipSnapshot relationship = EntityRelationshipSnapshot.captureOnOwner(target);
        EntityOverlaySource source = sourceFor(relationship);
        if (!eligible(target, current, relationship, source)) {
            return;
        }
        if (roleSnapshots.isTracked(target.getUniqueId())) {
            roleSnapshots.captureOnOwner(target, Set.of());
        }
        Location position = target.getLocation();
        if (position.getWorld() != anchor.world()) {
            return;
        }
        long now = System.currentTimeMillis();
        Hit hit = hits.get(insight.targetId());
        boolean struck = hit != null && hit.expiresAt() > now;
        double health = target.getHealth();
        EntityOverlayText.Snapshot snapshot = new EntityOverlayText.Snapshot(
            entityName(target), health, attribute(target, Attribute.MAX_HEALTH),
            struck ? hit.previousHealth() : health, struck ? hit.damage() : 0,
            attribute(target, Attribute.ATTACK_DAMAGE), attribute(target, Attribute.ARMOR),
            stackCount(target), target.getType().getKey().getKey(), 0);
        CellTarget cellTarget = new CellTarget(target, insight.targetId(), position.getX(), position.getY(),
            position.getZ(), snapshot,
            position.clone().add(0, target.getHeight() + current.verticalOffset(), 0), relationship, source);
        Player viewer = anchor.player();
        Runnable admit = () -> {
            if (!enabled() || settings != current || !viewer.isOnline() || !viewer.canSee(target)) {
                return;
            }
            EntityOverlayTarget overlay = admit(viewerId, cellTarget, current, sequence);
            if (overlay != null && overlay.dirty) {
                dispatchRender(overlay, viewer, current);
            }
        };
        if (!FoliaScheduler.runEntity(plugin, viewer, admit, 0, null)) {
            dropInsight(viewerId, insight);
        }
    }

    private void dispatchRender(EntityOverlayTarget overlay, Player representative, EntityOverlayDoc current) {
        if (driveTicks.get() < overlay.nextRenderTick || !overlay.rendering.compareAndSet(false, true)) {
            return;
        }
        int requested = overlay.source == null ? 0 : overlay.source.scanPolicy().intervalTicks();
        overlay.nextRenderTick = driveTicks.get() + (requested > 0 ? requested : current.updateIntervalTicks());
        Runnable retired = () -> overlay.rendering.set(false);
        if (!FoliaScheduler.runEntity(plugin, representative, () -> {
            try {
                render(overlay, representative, current);
            } finally {
                overlay.rendering.set(false);
            }
        }, 0, retired)) {
            retired.run();
        }
    }

    private void render(EntityOverlayTarget overlay, Player representative, EntityOverlayDoc current) {
        LivingEntity target = overlay.target;
        EntityOverlayTarget.Sample sample = overlay.sample;
        if (overlay.retired || !enabled() || settings != current || target == null || sample == null) {
            return;
        }
        overlay.dirty = false;
        renderPasses.incrementAndGet();
        synchronized (overlay.shared) {
            try {
                renderShared(overlay, representative, target, current, sample);
            } catch (RoleSnapshotPendingException pending) {
                overlay.dirty = true;
            } catch (RuntimeException failure) {
                overlay.shared.hide();
                Gloss.logExceptionStackThrottled(false, "entity-overlay-render", failure,
                    "Failed to render the entity overlay for %s.", overlay.targetId());
            }
        }
        for (UUID viewerId : overlay.audience.keySet()) {
            if (!overlay.personalViewers.contains(viewerId)) {
                syncNametag(overlay, viewerId);
            }
        }
        for (UUID viewerId : overlay.personalViewers) {
            dispatchPersonal(overlay, viewerId, current);
        }
        for (UUID viewerId : List.copyOf(overlay.personal.keySet())) {
            if (overlay.personalViewers.contains(viewerId)) {
                continue;
            }
            overlay.retirePersonal(viewerId);
        }
    }

    private void renderShared(EntityOverlayTarget overlay, Player representative, LivingEntity target,
                              EntityOverlayDoc current, EntityOverlayTarget.Sample sample) {
        EntityOverlayTarget.Render shared = overlay.shared;
        List<UUID> audience = new ArrayList<>(overlay.audience.size());
        for (UUID viewerId : overlay.audience.keySet()) {
            if (!overlay.personalViewers.contains(viewerId)) {
                audience.add(viewerId);
            }
        }
        if (audience.isEmpty()) {
            shared.hide();
            return;
        }
        if (!apply(overlay, shared, representative, target, current, sample.snapshot(), List.of(),
            sample.anchor())) {
            return;
        }
        syncWhitelist(shared, audience);
    }

    private void dispatchPersonal(EntityOverlayTarget overlay, UUID viewerId, EntityOverlayDoc current) {
        Player viewer = Bukkit.getPlayer(viewerId);
        if (viewer == null) {
            return;
        }
        if (!FoliaScheduler.runEntity(plugin, viewer, () -> renderPersonal(overlay, viewer, current), 0, null)) {
            overlay.retirePersonal(viewerId);
            syncNametag(overlay, viewerId);
        }
    }

    private void renderPersonal(EntityOverlayTarget overlay, Player viewer, EntityOverlayDoc current) {
        LivingEntity target = overlay.target;
        EntityOverlayTarget.Sample sample = overlay.sample;
        UUID viewerId = viewer.getUniqueId();
        if (overlay.retired || !enabled() || settings != current || target == null || sample == null
            || !overlay.personalViewers.contains(viewerId)) {
            return;
        }
        EntityOverlayTarget.Render personal = overlay.personal.computeIfAbsent(viewerId,
            ignored -> new EntityOverlayTarget.Render());
        synchronized (personal) {
            try {
                Insight insight = insightFor(viewerId, overlay.targetId());
                List<String> details = insight == null ? List.of() : insight.details();
                EntityOverlayText.Snapshot snapshot = personalSnapshot(sample, viewerId);
                if (apply(overlay, personal, viewer, target, current, snapshot, details, sample.anchor())) {
                    syncWhitelist(personal, List.of(viewerId));
                }
            } catch (RoleSnapshotPendingException pending) {
                overlay.dirty = true;
            } catch (RuntimeException failure) {
                personal.hide();
                Gloss.logExceptionStackThrottled(false, "entity-overlay-render", failure,
                    "Failed to render an entity overlay for %s.", viewerId);
            }
        }
        syncNametag(overlay, viewerId);
    }

    private void syncNametag(EntityOverlayTarget overlay, UUID viewerId) {
        EntityOverlayDoc current = settings;
        if (current == null || !current.overrideNametag()) {
            return;
        }
        overlays.computeIfPresent(overlay.targetId(), (targetId, active) -> {
            if (active == overlay) {
                updateNametag(overlay, viewerId);
            }
            return active;
        });
    }

    private void updateNametag(EntityOverlayTarget overlay, UUID viewerId) {
        LivingEntity target = overlay.target;
        EntityOverlayDoc current = settings;
        EntityOverlayTarget.Render render = overlay.personalViewers.contains(viewerId)
            ? overlay.personal.get(viewerId) : overlay.shared;
        Player viewer = Bukkit.getPlayer(viewerId);
        if (overlay.retired || !enabled() || current == null || !current.overrideNametag()
            || target == null || target instanceof Player || !overlay.audience.containsKey(viewerId)
            || render == null || render.display == null || !render.whitelist.contains(viewerId)
            || !render.admitted.contains(viewerId)
            || viewer == null || plugin.bedrock() != null && plugin.bedrock().isBedrock(viewerId)) {
            return;
        }
        EntityRelationshipSnapshot relationship = overlay.relationship;
        if (relationship != null) {
            suppression.admit(viewer, relationship.id(), relationship.teamEntry(), false);
        }
    }

    private EntityOverlayText.Snapshot personalSnapshot(EntityOverlayTarget.Sample sample, UUID viewerId) {
        if (!trackDistance) {
            return sample.snapshot();
        }
        EntityOverlayCell.Anchor anchor = anchors.get(viewerId);
        double distance = anchor == null ? 0
            : Math.sqrt(anchor.distanceSquared(sample.x(), sample.y(), sample.z()));
        EntityOverlayText.Snapshot base = sample.snapshot();
        return new EntityOverlayText.Snapshot(base.name(), base.health(), base.maxHealth(),
            base.previousHealth(), base.damage(), base.attack(), base.armor(), base.stackCount(),
            base.type(), distance);
    }

    private boolean apply(EntityOverlayTarget overlay, EntityOverlayTarget.Render render, Player viewer,
                          LivingEntity target, EntityOverlayDoc current,
                          EntityOverlayText.Snapshot snapshot, List<String> details, Location anchor) {
        ExprRoleSnapshot subject = roleSnapshots.view(target);
        ExprScope pairScope = new RoleSnapshotScope(GlossConditionScope.viewer(plugin, viewer),
            new ExprVariableContext(viewer, target, target, null, Map.of("subject", subject, "target", subject)));
        if (overlay.relationship != null && overlay.relationship.player()) {
            NametagService nametags = plugin.service(NametagService.class);
            snapshot = snapshot.withName(nametags == null ? overlay.relationship.teamEntry()
                : nametags.displayNameFromScope(viewer, pairScope));
        }
        EntityOverlayDoc selected = current;
        if (!presentationVariants.isEmpty()) {
            ExprScope scope = EntityOverlayText.variantScope(pairScope, snapshot, !details.isEmpty());
            for (PresentationVariant variant : presentationVariants) {
                if (variant.condition().matches(scope)) {
                    selected = variant.document();
                    break;
                }
            }
        }
        boolean presentationChanged = render.presentation != selected;
        current = selected;
        long render0 = plugin.text().renderGeneration();
        long emoji = TextPipeline.emojiGeneration();
        long animation = plugin.animations().generation();
        EntityOverlaySource source = overlay.source;
        boolean prepare = source != null || source != render.source || presentationChanged || render.prepared == null || refreshText || !snapshot.equals(render.snapshot)
            || !details.equals(render.details) || render0 != render.renderGeneration
            || emoji != render.emojiGeneration || animation != render.animationGeneration;
        EntityOverlaySource.Pane previousPane = render.pane;
        if (source != render.source && render.display != null) {
            render.hide();
        }
        if (prepare) {
            textPreparations.incrementAndGet();
            EntityOverlaySource.Pane pane = source == null ? null : source.prepare(
                new EntityOverlaySource.Context(viewer, overlay.relationship, snapshot, pairScope));
            if (source != null && pane == null) {
                render.hide();
                return false;
            }
            render.pane = pane;
            render.source = source;
            render.prepared = pane == null
                ? EntityOverlayText.prepare(plugin.text(), plugin.animator(), viewer, pairScope, current, snapshot, details)
                : pane.prepared();
            render.snapshot = snapshot;
            render.details = details;
            render.renderGeneration = render0;
            render.emojiGeneration = emoji;
            render.animationGeneration = animation;
        }
        EntityOverlayText.Prepared prepared = render.prepared;
        ParticleText.Rendered frame = prepared.frame(System.currentTimeMillis());
        if (frame.text().isEmpty()) {
            render.hide();
            return false;
        }
        EntityOverlaySource.Pane pane = render.pane;
        EntityOverlayDoc presentation = current;
        if (!overlay.attach(render, () -> createDisplay(overlay, render, target, presentation, anchor, pane))) {
            return false;
        }
        if (presentationChanged || pane != null && (previousPane == null
            || !pane.style().equals(previousPane.style()) || !pane.box().equals(previousPane.box())
            || pane.offset() != previousPane.offset())) {
            render.display.setStyle(pane == null ? current.style() : pane.style());
            render.display.setBox(pane == null ? current.box() : pane.box());
            render.display.setParticleLayers(pane == null ? current.particleLayers() : List.of());
            double offset = pane == null ? current.verticalOffset() : pane.offset();
            render.display.bindPosition(target, () -> target.getLocation().add(0, target.getHeight() + offset, 0));
        }
        render.presentation = current;
        if (!frame.equals(render.frame)) {
            render.display.setRenderedLines(lines(frame.text()));
            List<ParticleTextSpan> spans = new ArrayList<>(frame.spans().size());
            for (ParticleText.Span span : frame.spans()) {
                spans.add(new ParticleTextSpan(span.name(), span.start(), span.end()));
            }
            render.display.setRenderedParticleText(frame.text(), spans);
            render.frame = frame;
        }
        if (prepare) {
            render.display.bindRenderedFrames(prepared.animated()
                ? now -> lines(prepared.frame(now).text()) : null);
        }
        return true;
    }

    private TemporaryHologram createDisplay(EntityOverlayTarget overlay, EntityOverlayTarget.Render render,
                                            LivingEntity target, EntityOverlayDoc current, Location anchor,
                                            EntityOverlaySource.Pane pane) {
        double offset = pane == null ? current.verticalOffset() : pane.offset();
        TemporaryHologram display = plugin.holograms().createTemporary(
            "entity-overlay:" + target.getUniqueId(), anchor, Long.MAX_VALUE);
        plugin.holograms().setVisibilitySurface(display, pane == null
            ? VisibilityGovernor.Surface.OVERLAY : VisibilityGovernor.Surface.NAMEPLATE);
        EntityOverlaySource source = render.source;
        DisplayVisibility visibility = new DisplayVisibility(overlay, render, display, source);
        plugin.holograms().setVisibilityObserver(display, (viewerId, visible) ->
            displayVisibility(visibility, viewerId, visible));
        display.viewers().whitelist();
        display.setStyle(pane == null ? current.style() : pane.style());
        display.setBox(pane == null ? current.box() : pane.box());
        display.setParticleLayers(pane == null ? current.particleLayers() : List.of());
        display.bindPosition(target, () -> target.getLocation()
            .add(0, target.getHeight() + offset, 0));
        return display;
    }

    private record DisplayVisibility(EntityOverlayTarget overlay, EntityOverlayTarget.Render render,
                                     TemporaryHologram display, EntityOverlaySource source) { }

    private void displayVisibility(DisplayVisibility context, UUID viewerId, boolean visible) {
        EntityOverlayTarget overlay = context.overlay();
        EntityOverlayTarget.Render render = context.render();
        TemporaryHologram display = context.display();
        EntityOverlaySource source = context.source();
        if (render.display != null && render.display != display) {
            return;
        }
        if (!visible) {
            if (!render.admitted.remove(viewerId)) {
                return;
            }
            EntityOverlayTarget active = overlays.get(overlay.targetId());
            if (active != null && active != overlay) {
                return;
            }
            EntityOverlayTarget.Render replacement = active == null ? null
                : active.personalViewers.contains(viewerId) ? active.personal.get(viewerId) : active.shared;
            if (replacement != null && replacement != render && replacement.admitted.contains(viewerId)) {
                return;
            }
            suppression.retire(viewerId, overlay.targetId());
            if (source != null) {
                source.retired(viewerId, overlay.targetId());
            }
            return;
        }
        if (overlay.retired || overlays.get(overlay.targetId()) != overlay
            || !overlay.audience.containsKey(viewerId)) {
            return;
        }
        render.admitted.add(viewerId);
        Player viewer = Bukkit.getPlayer(viewerId);
        if (source != null && viewer != null && overlay.relationship != null) {
            source.displayed(viewer, overlay.relationship);
        }
        updateNametag(overlay, viewerId);
    }

    private static void syncWhitelist(EntityOverlayTarget.Render render, List<UUID> audience) {
        for (UUID viewerId : audience) {
            if (render.whitelist.add(viewerId)) {
                render.display.viewers().add(viewerId);
            }
        }
        render.whitelist.removeIf(viewerId -> {
            if (audience.contains(viewerId)) {
                return false;
            }
            render.display.viewers().remove(viewerId);
            return true;
        });
    }

    private static List<String> lines(String text) {
        List<String> lines = new ArrayList<>(4);
        int cursor = 0;
        while (true) {
            int split = text.indexOf('\n', cursor);
            if (split < 0) {
                lines.add(text.substring(cursor));
                return lines;
            }
            lines.add(text.substring(cursor, split));
            cursor = split + 1;
        }
    }

    private boolean eligible(LivingEntity target, EntityOverlayDoc current,
                             EntityRelationshipSnapshot relationship, EntityOverlaySource source) {
        return target.isValid() && !target.isDead()
            && (source != null || !relationship.invisible() && !relationship.spectator()
                && (current.includePlayers() || !relationship.player())
                && !current.excludedEntityTypes().contains(target.getType().name()));
    }

    private EntityOverlaySource sourceFor(EntityRelationshipSnapshot target) {
        for (EntityOverlaySource source : sources) {
            if (source.wants(target)) {
                return source;
            }
        }
        return null;
    }

    private static boolean isLivingCandidate(Entity entity) {
        return entity instanceof LivingEntity;
    }

    private Insight insightFor(UUID viewerId, UUID targetId) {
        Insight current = insights.get(viewerId);
        return current != null && current.targetId().equals(targetId)
            && current.expiresAt() > System.currentTimeMillis() ? current : null;
    }

    private void linkInsight(UUID viewerId, Insight next) {
        insightTargets.computeIfAbsent(next.targetId(), ignored -> ConcurrentHashMap.newKeySet()).add(viewerId);
        Insight previous = insights.put(viewerId, next);
        if (previous != null) {
            detachInsight(viewerId, previous);
        }
        EntityOverlayTarget overlay = overlays.get(next.targetId());
        if (overlay != null) {
            overlay.dirty = true;
        }
    }

    private void dropInsight(UUID viewerId, Insight expected) {
        insights.computeIfPresent(viewerId, (id, current) -> {
            if (current != expected) {
                return current;
            }
            unlinkInsight(id, current);
            return null;
        });
    }

    private void unlinkInsight(UUID viewerId, Insight current) {
        detachInsight(viewerId, current);
        EntityOverlayTarget overlay = overlays.get(current.targetId());
        if (overlay != null) {
            overlay.personalViewers.remove(viewerId);
            overlay.dirty = true;
        }
    }

    private void detachInsight(UUID viewerId, Insight current) {
        insightTargets.computeIfPresent(current.targetId(), (ignored, viewers) -> {
            viewers.remove(viewerId);
            return viewers.isEmpty() ? null : viewers;
        });
    }

    private static double attribute(LivingEntity entity, Attribute attribute) {
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? 0 : instance.getValue();
    }

    private String entityName(LivingEntity entity) {
        if (ecoMobsPresent && entity instanceof Mob mob) {
            try {
                String name = EcoMobsEntityNames.resolve(mob);
                if (name != null) {
                    return name;
                }
            } catch (RuntimeException | LinkageError failure) {
                Gloss.logExceptionStackThrottled(false, "entity-overlay-ecomobs", failure,
                    "Failed to resolve the EcoMobs name for %s.", entity.getUniqueId());
            }
        }
        return entity.getCustomName();
    }

    private int stackCount(LivingEntity entity) {
        if (reactPresent) {
            Integer persisted = entity.getPersistentDataContainer().get(REACT_STACK_COUNT, PersistentDataType.INTEGER);
            if (persisted != null) {
                return Math.max(1, persisted);
            }
        }
        return stackCounts.getOrDefault(entity.getUniqueId(), 1);
    }

    private void removeEntity(UUID entityId) {
        roleSnapshots.forget(entityId);
        hits.remove(entityId);
        stackCounts.remove(entityId);
        Set<UUID> viewers = insightTargets.remove(entityId);
        if (viewers != null) {
            for (UUID viewerId : viewers) {
                insights.computeIfPresent(viewerId,
                    (ignored, current) -> current.targetId().equals(entityId) ? null : current);
            }
            removalMutations.incrementAndGet();
        }
        EntityOverlayTarget overlay = overlays.remove(entityId);
        if (overlay != null) {
            destroy(overlay);
            removalMutations.incrementAndGet();
        }
    }

    private void dropViewer(UUID viewerId, long sequence) {
        EntityOverlayCell.Anchor current = anchors.get(viewerId);
        if (current != null && current.sequence() > sequence) {
            return;
        }
        if (current == null && !insights.containsKey(viewerId)) {
            return;
        }
        removeViewer(viewerId);
    }

    private void removeViewer(UUID viewerId) {
        anchors.remove(viewerId);
        Insight insight = insights.remove(viewerId);
        if (insight != null) {
            detachInsight(viewerId, insight);
        }
        for (EntityOverlayTarget overlay : overlays.values()) {
            if (overlay.audience.remove(viewerId) == null) {
                continue;
            }
            retired(overlay, List.of(viewerId));
            overlay.personalViewers.remove(viewerId);
            overlay.dirty = true;
            overlay.retirePersonal(viewerId);
            EntityOverlayTarget.Render shared = overlay.shared;
            synchronized (shared) {
                if (shared.display != null && shared.whitelist.remove(viewerId)) {
                    shared.display.viewers().remove(viewerId);
                }
            }
        }
    }

    private record Insight(Plugin owner, LivingEntity target, UUID targetId, List<String> details, long expiresAt) {
    }

    private record CellTarget(LivingEntity entity, UUID targetId, double x, double y, double z,
                              EntityOverlayText.Snapshot snapshot, Location anchor, EntityRelationshipSnapshot relationship,
                              EntityOverlaySource source) {
    }

    private record Admission(CellTarget target, double distanceSquared) {
    }

    private record Hit(double previousHealth, double damage, long expiresAt) {
    }


    @Override
    public Map<String, DocumentRegistry<?>> registries() {
        return Map.of("entity-overlays", registry);
    }
}
