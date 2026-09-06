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
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EntityOverlayService implements Listener {
    private static final NamespacedKey REACT_STACK_COUNT = new NamespacedKey("react", "react-stack-count");
    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<EntityOverlayDoc> registry;
    private final ConcurrentMap<UUID, ViewerState> viewers = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Insight> insights = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Integer> stackCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Hit> hits = new ConcurrentHashMap<>();
    private final Set<Plugin> restrictions = ConcurrentHashMap.newKeySet();
    private volatile EntityOverlayDoc settings;
    private volatile boolean started;
    private volatile boolean reactPresent;
    private volatile boolean refreshText;
    private volatile boolean trackDistance;
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
        int count = 0;
        for (ViewerState viewer : viewers.values()) {
            count += viewer.overlays.size();
        }
        return count;
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
        insights.put(viewer.getUniqueId(), new Insight(owner, target, lines,
            System.currentTimeMillis() + Math.clamp(durationMs, 100, 10000)));
        return true;
    }

    public void clearInsight(Plugin owner, UUID viewerId) {
        insights.computeIfPresent(viewerId, (ignored, current) -> current.owner() == owner ? null : current);
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
        insights.entrySet().removeIf(entry -> entry.getValue().owner() == owner);
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
        trackDistance = updated != null && usesDistance(updated);
        if (enabled()) {
            taskId = plugin.scheduler().sr(this::drive, settings.updateIntervalTicks());
        }
    }

    private void stopDriver() {
        if (taskId != -1) {
            plugin.scheduler().csr(taskId);
            taskId = -1;
        }
        for (UUID viewer : List.copyOf(viewers.keySet())) {
            removeViewer(viewer);
        }
    }

    private void drive() {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        hits.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        insights.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        for (Player player : Bukkit.getOnlinePlayers()) {
            ViewerState state = viewers.computeIfAbsent(player.getUniqueId(), ignored -> new ViewerState());
            if (!state.scanning.compareAndSet(false, true)) {
                continue;
            }
            Runnable retired = () -> {
                state.scanning.set(false);
                removeViewer(player.getUniqueId(), state);
            };
            if (!FoliaScheduler.runEntity(plugin, player, () -> scan(player, state), 0, retired)) {
                retired.run();
            }
        }
    }

    private void scan(Player player, ViewerState state) {
        try {
            EntityOverlayDoc current = settings;
            if (!enabled() || !state.active || !player.isOnline() || player.isDead()
                || player.getGameMode() == GameMode.SPECTATOR
                || current.blacklistWorlds().contains(player.getWorld().getName())) {
                clearOverlays(state);
                return;
            }
            Location origin = player.getLocation();
            Set<UUID> selected = new HashSet<>();
            Insight insight = insight(player.getUniqueId(), System.currentTimeMillis());
            if (insight != null) {
                select(player, state, origin, insight.target(), selected, current);
            }
            if (restrictions.isEmpty()) {
                for (Entity candidate : player.getNearbyEntities(current.range(), current.range(), current.range())) {
                    if (selected.size() >= current.maxEntitiesPerViewer()) {
                        break;
                    }
                    if (candidate instanceof LivingEntity living) {
                        select(player, state, origin, living, selected, current);
                    }
                }
            }
            for (Map.Entry<UUID, Overlay> entry : state.overlays.entrySet()) {
                if (!selected.contains(entry.getKey()) && state.overlays.remove(entry.getKey(), entry.getValue())) {
                    entry.getValue().destroy();
                }
            }
        } finally {
            state.scanning.set(false);
        }
    }

    private void select(Player player, ViewerState state, Location origin, LivingEntity target,
                        Set<UUID> selected, EntityOverlayDoc current) {
        UUID targetId = target.getUniqueId();
        if (!state.active || targetId.equals(player.getUniqueId())
            || !current.includePlayers() && target instanceof Player
            || current.excludedEntityTypes().contains(target.getType().name())
            || !player.canSee(target) || !selected.add(targetId)) {
            return;
        }
        Overlay overlay = state.overlays.computeIfAbsent(targetId, ignored -> new Overlay());
        if (!overlay.sampling.compareAndSet(false, true)) {
            return;
        }
        Runnable retired = () -> {
            overlay.sampling.set(false);
            state.overlays.remove(targetId, overlay);
            overlay.destroy();
        };
        if (!FoliaScheduler.runEntity(plugin, target,
            () -> update(player, state, overlay, target, origin, current), 0, retired)) {
            retired.run();
        }
    }

    private void update(Player player, ViewerState state, Overlay overlay, LivingEntity target,
                        Location origin, EntityOverlayDoc current) {
        boolean dispatched = false;
        try {
            synchronized (overlay) {
                if (!state.active || overlay.retired || !enabled() || settings != current) {
                    return;
                }
                Location position = target.getLocation();
                long now = System.currentTimeMillis();
                Insight insight = insight(player.getUniqueId(), now);
                boolean selected = insight != null && insight.target().getUniqueId().equals(target.getUniqueId());
                if (!target.isValid() || target.isDead() || target.isInvisible()
                    || !origin.getWorld().equals(position.getWorld())
                    || !selected && origin.distanceSquared(position) > current.range() * current.range()
                    || !current.includePlayers() && target instanceof Player
                    || target instanceof Player other && other.getGameMode() == GameMode.SPECTATOR
                    || current.excludedEntityTypes().contains(target.getType().name())
                    || !restrictions.isEmpty() && !selected) {
                    overlay.hide();
                    return;
                }
                Hit hit = hits.get(target.getUniqueId());
                boolean struck = hit != null && hit.expiresAt() > now;
                double health = target.getHealth();
                EntityOverlayText.Snapshot snapshot = new EntityOverlayText.Snapshot(
                    target.getCustomName(), health, attribute(target, Attribute.MAX_HEALTH),
                    struck ? hit.previousHealth() : health, struck ? hit.damage() : 0,
                    attribute(target, Attribute.ATTACK_DAMAGE), attribute(target, Attribute.ARMOR),
                    stackCount(target), target.getType().getKey().getKey(), trackDistance ? origin.distance(position) : 0);
                OverlaySample sample = new OverlaySample(target.getUniqueId(), snapshot,
                    position.clone(), position.add(0, target.getHeight() + current.verticalOffset(), 0));
                Runnable retired = () -> {
                    overlay.sampling.set(false);
                    state.overlays.remove(sample.targetId(), overlay);
                    overlay.destroy();
                };
                dispatched = FoliaScheduler.runEntity(plugin, player, () -> {
                    try {
                        render(player, state, overlay, target, current, sample);
                    } finally {
                        overlay.sampling.set(false);
                    }
                }, 0, retired);
                if (!dispatched) {
                    retired.run();
                }
            }
        } catch (RuntimeException failure) {
            synchronized (overlay) {
                overlay.hide();
            }
            Gloss.logExceptionStackThrottled(false, "entity-overlay-sample", failure,
                "Failed to sample an entity overlay for %s.", player.getUniqueId());
        } finally {
            if (!dispatched) {
                overlay.sampling.set(false);
            }
        }
    }

    private void render(Player player, ViewerState state, Overlay overlay, LivingEntity target,
                        EntityOverlayDoc current, OverlaySample sample) {
        synchronized (overlay) {
            if (!state.active || overlay.retired || !enabled() || settings != current) {
                return;
            }
            if (!player.isOnline() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR
                || player.getWorld() != sample.anchor().getWorld() || !player.canSee(target)) {
                overlay.hide();
                return;
            }
            Insight insight = insight(player.getUniqueId(), System.currentTimeMillis());
            boolean selected = insight != null && insight.target().getUniqueId().equals(sample.targetId());
            if (!restrictions.isEmpty() && !selected
                || !selected && player.getLocation().distanceSquared(sample.position()) > current.range() * current.range()) {
                overlay.hide();
                return;
            }
            List<String> details = selected ? insight.details() : List.of();
            try {
                long renderGeneration = plugin.text().renderGeneration();
                long emojiGeneration = TextPipeline.emojiGeneration();
                long animationGeneration = plugin.animations().generation();
                boolean prepare = overlay.prepared == null || refreshText
                    || !sample.snapshot().equals(overlay.snapshot) || !details.equals(overlay.details)
                    || renderGeneration != overlay.renderGeneration || emojiGeneration != overlay.emojiGeneration
                    || animationGeneration != overlay.animationGeneration;
                if (prepare) {
                    overlay.prepared = EntityOverlayText.prepare(plugin, player, current,
                        sample.snapshot(), details);
                    overlay.snapshot = sample.snapshot();
                    overlay.details = details;
                    overlay.renderGeneration = renderGeneration;
                    overlay.emojiGeneration = emojiGeneration;
                    overlay.animationGeneration = animationGeneration;
                }
                EntityOverlayText.Prepared prepared = overlay.prepared;
                ParticleText.Rendered frame = prepared.frame(System.currentTimeMillis());
                if (frame.text().isEmpty()) {
                    overlay.hide();
                    return;
                }
                if (overlay.display == null) {
                    TemporaryHologram display = plugin.holograms().createTemporary(
                        "entity-overlay:" + player.getUniqueId() + ":" + sample.targetId(),
                        sample.anchor(), Long.MAX_VALUE);
                    display.viewers().whitelist();
                    display.viewers().add(player.getUniqueId());
                    display.setStyle(current.style());
                    display.setBox(current.box());
                    display.setParticleLayers(current.particleLayers());
                    display.bindPosition(target, () -> target.getLocation()
                        .add(0, target.getHeight() + current.verticalOffset(), 0));
                    overlay.display = display;
                }
                if (!frame.equals(overlay.frame)) {
                    overlay.display.setRenderedLines(List.of(frame.text().split("\\n", -1)));
                    List<ParticleTextSpan> spans = new ArrayList<>(frame.spans().size());
                    for (ParticleText.Span span : frame.spans()) {
                        spans.add(new ParticleTextSpan(span.name(), span.start(), span.end()));
                    }
                    overlay.display.setRenderedParticleText(frame.text(), spans);
                    overlay.frame = frame;
                }
                if (prepare) {
                    overlay.display.bindRenderedFrames(prepared.animated()
                        ? now -> List.of(prepared.frame(now).text().split("\\n", -1)) : null);
                }
            } catch (RuntimeException failure) {
                overlay.hide();
                Gloss.logExceptionStackThrottled(false, "entity-overlay-render", failure,
                    "Failed to render an entity overlay for %s.", player.getUniqueId());
            }
        }
    }

    private static boolean usesDistance(EntityOverlayDoc settings) {
        if (settings.show().expression().contains("entity.distance")) {
            return true;
        }
        for (EntityOverlayDoc.Line line : settings.lines()) {
            if (line.text().contains("{distance}") || line.text().contains("entity.distance")
                || line.show().expression().contains("entity.distance")) {
                return true;
            }
        }
        return false;
    }

    private Insight insight(UUID viewer, long now) {
        Insight current = insights.get(viewer);
        return current != null && current.expiresAt() > now ? current : null;
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
        insights.entrySet().removeIf(entry -> entry.getValue().target().getUniqueId().equals(entityId));
        for (ViewerState viewer : viewers.values()) {
            Overlay overlay = viewer.overlays.remove(entityId);
            if (overlay != null) {
                overlay.destroy();
            }
        }
    }

    private void removeViewer(UUID viewerId) {
        insights.remove(viewerId);
        ViewerState state = viewers.remove(viewerId);
        if (state != null) {
            state.active = false;
            clearOverlays(state);
        }
    }

    private void removeViewer(UUID viewerId, ViewerState expected) {
        if (viewers.remove(viewerId, expected)) {
            insights.remove(viewerId);
        }
        expected.active = false;
        clearOverlays(expected);
    }

    private static void clearOverlays(ViewerState state) {
        for (UUID targetId : List.copyOf(state.overlays.keySet())) {
            Overlay overlay = state.overlays.remove(targetId);
            if (overlay != null) {
                overlay.destroy();
            }
        }
    }

    private record Insight(Plugin owner, LivingEntity target, List<String> details, long expiresAt) {
    }

    private record OverlaySample(UUID targetId, EntityOverlayText.Snapshot snapshot,
                                 Location position, Location anchor) {
    }

    private record Hit(double previousHealth, double damage, long expiresAt) {
    }

    private static final class ViewerState {
        private final ConcurrentMap<UUID, Overlay> overlays = new ConcurrentHashMap<>();
        private final AtomicBoolean scanning = new AtomicBoolean();
        private volatile boolean active = true;
    }

    private static final class Overlay {
        private final AtomicBoolean sampling = new AtomicBoolean();
        private TemporaryHologram display;
        private ParticleText.Rendered frame;
        private EntityOverlayText.Prepared prepared;
        private EntityOverlayText.Snapshot snapshot;
        private List<String> details = List.of();
        private long renderGeneration;
        private long emojiGeneration;
        private long animationGeneration;
        private boolean retired;

        private synchronized void destroy() {
            retired = true;
            hide();
        }

        private void hide() {
            if (display != null) {
                display.destroy();
                display = null;
            }
            frame = null;
            prepared = null;
            snapshot = null;
            details = List.of();
        }
    }
}
