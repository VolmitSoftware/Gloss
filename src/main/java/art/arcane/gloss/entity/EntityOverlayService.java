package art.arcane.gloss.entity;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.api.ParticleTextSpan;
import art.arcane.gloss.particle.ParticleText;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class EntityOverlayService implements Listener {
    private static final NamespacedKey REACT_STACK_COUNT = new NamespacedKey("react", "react-stack-count");
    private static final long ANCHOR_GRACE_DRIVES = 1L;
    private static final long AUDIENCE_GRACE_DRIVES = 1L;

    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<EntityOverlayDoc> registry;
    private final ConcurrentMap<UUID, EntityOverlayCell.Anchor> anchors = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, EntityOverlayTarget> overlays = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Insight> insights = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Set<UUID>> insightTargets = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Integer> stackCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Hit> hits = new ConcurrentHashMap<>();
    private final Set<Plugin> restrictions = ConcurrentHashMap.newKeySet();
    private final AtomicLong renderPasses = new AtomicLong();
    private final AtomicLong textPreparations = new AtomicLong();
    private final AtomicLong removalMutations = new AtomicLong();
    private final AtomicLong driveSequence = new AtomicLong();
    private volatile EntityOverlayDoc settings;
    private volatile boolean started;
    private volatile boolean reactPresent;
    private volatile boolean refreshText;
    private volatile boolean trackDistance;
    private volatile boolean personalText;
    private volatile long renderGeneration = -1;
    private volatile long emojiGeneration = -1;
    private volatile long animationGeneration = -1;
    private int taskId = -1;

    public EntityOverlayService(Gloss plugin) {
        this.plugin = plugin;
        File folder = new File(plugin.getDataFolder(), EntityOverlayDoc.KIND);
        defaults = new ShippedDefaults(EntityOverlayDoc.KIND, folder,
            ShippedDocumentCatalog.ENTITY_OVERLAYS.names());
        registry = DocumentRegistry.folder(EntityOverlayDoc.KIND, folder, EntityOverlayDoc::parse,
            EntityOverlayDoc::revision);
    }

    public void enable() {
        started = true;
        reactPresent = plugin.getServer().getPluginManager().isPluginEnabled("React");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        reload();
        plugin.watchdog().register(EntityOverlayDoc.KIND, this::poll);
    }

    public void disable() {
        started = false;
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
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("React")) {
            reactPresent = true;
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
        refreshText = updated != null && EntityOverlayText.refreshRequired(updated);
        trackDistance = updated != null && EntityOverlayText.usesDistance(updated);
        personalText = updated != null && EntityOverlayText.personalRequired(updated,
            plugin.animations()::framesViewerSpecific);
        if (enabled()) {
            taskId = plugin.scheduler().sr(this::drive, settings.updateIntervalTicks());
        }
    }

    private void stopDriver() {
        if (taskId != -1) {
            plugin.scheduler().csr(taskId);
            taskId = -1;
        }
        anchors.clear();
        insights.clear();
        insightTargets.clear();
        for (UUID targetId : List.copyOf(overlays.keySet())) {
            EntityOverlayTarget overlay = overlays.remove(targetId);
            if (overlay != null) {
                overlay.destroy();
            }
        }
    }

    private void drive() {
        EntityOverlayDoc current = settings;
        if (!enabled() || current == null) {
            return;
        }
        long sequence = driveSequence.incrementAndGet();
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
        if (!drift && !refreshText && !trackDistance) {
            return;
        }
        for (EntityOverlayTarget overlay : overlays.values()) {
            overlay.dirty = true;
        }
    }

    private void sweepOverlays(long sequence) {
        for (EntityOverlayTarget overlay : overlays.values()) {
            boolean changed = overlay.audience.entrySet()
                .removeIf(entry -> sequence - entry.getValue() > AUDIENCE_GRACE_DRIVES);
            if (overlay.audience.isEmpty()) {
                if (overlays.remove(overlay.targetId(), overlay)) {
                    overlay.destroy();
                }
                continue;
            }
            if (changed) {
                overlay.personalViewers.removeIf(viewerId -> !overlay.audience.containsKey(viewerId));
                overlay.dirty = true;
            }
        }
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
            BoundingBox box = cell.box(current.range());
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
                anchor.box(current.range()), anchor.x(), anchor.z(), folia());
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
            if (!eligible(target, current)) {
                continue;
            }
            Location position = target.getLocation();
            Hit hit = hits.get(target.getUniqueId());
            boolean struck = hit != null && hit.expiresAt() > now;
            double health = target.getHealth();
            EntityOverlayText.Snapshot snapshot = new EntityOverlayText.Snapshot(
                target.getCustomName(), health, attribute(target, Attribute.MAX_HEALTH),
                struck ? hit.previousHealth() : health, struck ? hit.damage() : 0,
                attribute(target, Attribute.ATTACK_DAMAGE), attribute(target, Attribute.ARMOR),
                stackCount(target), target.getType().getKey().getKey(), 0);
            targets.add(new CellTarget(target, target.getUniqueId(), position.getX(), position.getY(),
                position.getZ(), snapshot,
                position.clone().add(0, target.getHeight() + current.verticalOffset(), 0)));
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
        UUID viewerId = anchor.viewerId();
        double rangeSquared = current.range() * current.range();
        List<Admission> admissions = new ArrayList<>(Math.min(targets.size(), current.maxEntitiesPerViewer()));
        for (CellTarget target : targets) {
            if (viewerId.equals(target.targetId())) {
                continue;
            }
            double distanceSquared = anchor.distanceSquared(target.x(), target.y(), target.z());
            if (distanceSquared > rangeSquared || !viewer.canSee(target.entity())) {
                continue;
            }
            admissions.add(new Admission(target, distanceSquared));
        }
        if (admissions.size() > current.maxEntitiesPerViewer()
            || overlays.size() + admissions.size() > current.maxActiveOverlays()) {
            admissions.sort((left, right) -> Double.compare(left.distanceSquared(), right.distanceSquared()));
        }
        int admitted = 0;
        for (Admission admission : admissions) {
            if (admitted >= current.maxEntitiesPerViewer()) {
                break;
            }
            EntityOverlayTarget overlay = admit(viewerId, admission.target(), current, sequence);
            if (overlay == null) {
                continue;
            }
            admitted++;
            if (overlay.dirty) {
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
        overlay.publish(target.snapshot(), target.anchor(), target.x(), target.y(), target.z());
        boolean personal = personalText || insightFor(viewerId, target.targetId()) != null;
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
        if (anchor == null || !eligible(target, current)) {
            return;
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
            target.getCustomName(), health, attribute(target, Attribute.MAX_HEALTH),
            struck ? hit.previousHealth() : health, struck ? hit.damage() : 0,
            attribute(target, Attribute.ATTACK_DAMAGE), attribute(target, Attribute.ARMOR),
            stackCount(target), target.getType().getKey().getKey(), 0);
        CellTarget cellTarget = new CellTarget(target, insight.targetId(), position.getX(), position.getY(),
            position.getZ(), snapshot,
            position.clone().add(0, target.getHeight() + current.verticalOffset(), 0));
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
        if (!overlay.rendering.compareAndSet(false, true)) {
            return;
        }
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
            } catch (RuntimeException failure) {
                overlay.shared.hide();
                Gloss.logExceptionStackThrottled(false, "entity-overlay-render", failure,
                    "Failed to render the entity overlay for %s.", overlay.targetId());
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
            } catch (RuntimeException failure) {
                personal.hide();
                Gloss.logExceptionStackThrottled(false, "entity-overlay-render", failure,
                    "Failed to render an entity overlay for %s.", viewerId);
            }
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
        long render0 = plugin.text().renderGeneration();
        long emoji = TextPipeline.emojiGeneration();
        long animation = plugin.animations().generation();
        boolean prepare = render.prepared == null || refreshText || !snapshot.equals(render.snapshot)
            || !details.equals(render.details) || render0 != render.renderGeneration
            || emoji != render.emojiGeneration || animation != render.animationGeneration;
        if (prepare) {
            textPreparations.incrementAndGet();
            render.prepared = EntityOverlayText.prepare(plugin, viewer, current, snapshot, details);
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
        if (!overlay.attach(render, () -> createDisplay(target, current, anchor))) {
            return false;
        }
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

    private TemporaryHologram createDisplay(LivingEntity target, EntityOverlayDoc current, Location anchor) {
        TemporaryHologram display = plugin.holograms().createTemporary(
            "entity-overlay:" + target.getUniqueId(), anchor, Long.MAX_VALUE);
        display.viewers().whitelist();
        display.setStyle(current.style());
        display.setBox(current.box());
        display.setParticleLayers(current.particleLayers());
        display.bindPosition(target, () -> target.getLocation()
            .add(0, target.getHeight() + current.verticalOffset(), 0));
        return display;
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

    private boolean eligible(LivingEntity target, EntityOverlayDoc current) {
        return target.isValid() && !target.isDead() && !target.isInvisible()
            && (current.includePlayers() || !(target instanceof Player))
            && !(target instanceof Player other && other.getGameMode() == GameMode.SPECTATOR)
            && !current.excludedEntityTypes().contains(target.getType().name());
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
            overlay.destroy();
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
                              EntityOverlayText.Snapshot snapshot, Location anchor) {
    }

    private record Admission(CellTarget target, double distanceSquared) {
    }

    private record Hit(double previousHealth, double damage, long expiresAt) {
    }

}
