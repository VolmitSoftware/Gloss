package art.arcane.gloss.indicator;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.GlossConditionContext;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.service.AdmissionBudget;
import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import art.arcane.volmlib.util.scheduling.SlidingWindowRateLimiter;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class DamageIndicatorsService implements Listener {
    private static final long DEBOUNCE_MS = 150L;
    private static final long SAMPLE_DELAY_TICKS = 2L;
    private static final int DRIVER_INTERVAL_TICKS = 2;
    private static final long BUDGET_WINDOW_MS = 1000L;
    static final int MAX_LIVE_INDICATORS = 2048;

    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<DamageIndicatorSettingsDoc> settings;
    private final Map<UUID, Long> debounce = new ConcurrentHashMap<>();
    private final SlidingWindowRateLimiter rateLimiter = new SlidingWindowRateLimiter();
    private final Map<String, LiveIndicator> live = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong lifecycleEpoch = new AtomicLong();
    private final IndicatorBudget budget = new IndicatorBudget(BUDGET_WINDOW_MS);
    private final AdmissionBudget admissions = new AdmissionBudget(MAX_LIVE_INDICATORS);
    private final BoundedConditionErrorCallback conditionErrors = BoundedConditionErrorCallback.bounded(
        32, error -> Gloss.logExceptionStackThrottled(false,
            "damage-indicator-condition-" + error.path(), error.cause(),
            "Could not evaluate damage-indicator condition %s.", error.path()));
    private final DamageIndicatorCriticality criticality;

    private volatile ActiveSettings activeSettings = ActiveSettings.defaults();
    private int driverTaskId = -1;
    private volatile boolean started;
    private volatile boolean listening;

    public DamageIndicatorsService(Gloss plugin) {
        this.plugin = plugin;
        criticality = DamageIndicatorCriticality.load();
        if (plugin == null) {
            defaults = null;
            settings = null;
            return;
        }
        File folder = new File(plugin.getDataFolder(), DamageIndicatorSettingsDoc.KIND);
        defaults = new ShippedDefaults(DamageIndicatorSettingsDoc.KIND, folder,
            ShippedDocumentCatalog.DAMAGE_INDICATORS.names());
        settings = DocumentRegistry.folder(DamageIndicatorSettingsDoc.KIND, folder,
            DamageIndicatorSettingsDoc::parse, DamageIndicatorSettingsDoc::revision);
    }

    public void enable() {
        if (started) {
            return;
        }
        started = true;
        loadSettings();
        plugin.watchdog().register(DamageIndicatorSettingsDoc.KIND, this::pollSettings);
        applyFeatureState();
    }

    public void disable() {
        unregister();
        destroyAll();
        debounce.clear();
        if (started) {
            plugin.watchdog().unregister(DamageIndicatorSettingsDoc.KIND);
            settings.close();
            started = false;
        }
    }

    public void reload() {
        if (!started) {
            enable();
            return;
        }
        loadSettings();
        applyFeatureState();
    }

    public void reloadSettings() {
        if (started) {
            loadSettings();
        }
    }

    public List<String> resetToDefault(String nameOrStar) {
        List<String> restored = defaults.resetToDefault(nameOrStar);
        if (!restored.isEmpty()) {
            loadSettings();
        }
        return restored;
    }

    public int activeCount() {
        return admissions.active();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        ActiveSettings snapshot = activeSettings;
        sample(event.getEntity(), snapshot,
            DamageIndicatorEventSnapshot.damage(event, plugin, criticality));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        ActiveSettings snapshot = activeSettings;
        sample(event.getEntity(), snapshot, DamageIndicatorEventSnapshot.healing(event));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onViewerJoin(PlayerJoinEvent event) {
        Player viewer = event.getPlayer();
        reevaluateViewer(viewer);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onViewerChangedWorld(PlayerChangedWorldEvent event) {
        reevaluateViewer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onViewerRespawn(PlayerRespawnEvent event) {
        reevaluateViewer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onViewerMove(PlayerMoveEvent event) {
        Location destination = event.getTo();
        if (destination == null || sameChunk(event.getFrom(), destination)) {
            return;
        }
        reevaluateViewer(event.getPlayer());
    }

    private void register() {
        if (listening) {
            return;
        }
        lifecycleEpoch.incrementAndGet();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        listening = true;
        driverTaskId = plugin.scheduler().ar(this::drive, DRIVER_INTERVAL_TICKS);
    }

    private void unregister() {
        if (!listening) {
            return;
        }
        listening = false;
        lifecycleEpoch.incrementAndGet();
        if (driverTaskId != -1) {
            plugin.scheduler().car(driverTaskId);
            driverTaskId = -1;
        }
        HandlerList.unregisterAll(this);
    }

    private void applyFeatureState() {
        if (plugin.cfg().indicators().enabled()) {
            if (!listening) {
                register();
            }
            return;
        }
        unregister();
        destroyAll();
        debounce.clear();
    }

    private void loadSettings() {
        if (plugin.cfg().indicators().enabled()) {
            defaults.extractMissing();
        }
        settings.reload();
        GlossDocument<DamageIndicatorSettingsDoc> document =
            settings.get(DamageIndicatorSettingsDoc.DEFAULT_ID);
        applySettings(document == null ? DamageIndicatorSettingsDoc.DEFAULTS : document.value());
    }

    private void pollSettings() {
        DocumentDelta delta = settings.poll();
        if (delta.isEmpty()) {
            return;
        }
        GlossDocument<DamageIndicatorSettingsDoc> document =
            settings.get(delta, DamageIndicatorSettingsDoc.DEFAULT_ID);
        DamageIndicatorSettingsDoc updated = document == null
            ? DamageIndicatorSettingsDoc.DEFAULTS
            : document.value();
        if (!settings.dispatch(delta, task -> SchedulerUtils.runGlobal(plugin, task),
            () -> applySettings(updated))) {
            Gloss.warnThrottled("damage-indicator-hotload-scheduling",
                "Could not apply hot-reloaded damage-indicator settings on the server thread; "
                    + "the change will be retried.");
        }
    }

    private void applySettings(DamageIndicatorSettingsDoc updated) {
        ActiveSettings previous = activeSettings;
        activeSettings = new ActiveSettings(updated, DamageIndicatorConditionPlan.compile(updated));
        if (listening && !previous.document().equals(updated)) {
            lifecycleEpoch.incrementAndGet();
            destroyAll();
        }
    }

    private void drive() {
        long now = nowMs();
        Iterator<Map.Entry<UUID, Long>> debounced = debounce.entrySet().iterator();
        while (debounced.hasNext()) {
            if (debounced.next().getValue() <= now) {
                debounced.remove();
            }
        }
        Iterator<Map.Entry<String, LiveIndicator>> indicators = live.entrySet().iterator();
        while (indicators.hasNext()) {
            Map.Entry<String, LiveIndicator> entry = indicators.next();
            LiveIndicator indicator = entry.getValue();
            if (indicator.expiresAtMs <= now && live.remove(entry.getKey(), indicator)) {
                indicator.retire(true);
            } else if (indicator.conditions.dynamicShow()) {
                scheduleAudience(indicator);
            }
        }
    }

    private void sample(Entity entity, ActiveSettings snapshot,
                        DamageIndicatorEventSnapshot eventSnapshot) {
        GlossConfig.Indicators cfg = plugin.cfg().indicators();
        if (!cfg.enabled()) {
            return;
        }
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        long now = nowMs();
        if (budget.saturated(now, snapshot.document().limits().maxPerSecond())) {
            return;
        }
        if (!claimDebounce(debounce, living.getUniqueId(), now, DEBOUNCE_MS)) {
            return;
        }

        double before = living.getHealth();
        FoliaScheduler.runEntity(plugin, living,
            () -> compare(living, before, snapshot, eventSnapshot), SAMPLE_DELAY_TICKS);
    }

    static boolean claimDebounce(Map<UUID, Long> debounce, UUID entityId, long nowMs, long windowMs) {
        Long existing = debounce.putIfAbsent(entityId, nowMs + windowMs);
        if (existing == null) {
            return true;
        }
        if (existing > nowMs) {
            return false;
        }
        return debounce.replace(entityId, existing, nowMs + windowMs);
    }

    private void compare(LivingEntity living, double before, ActiveSettings snapshot,
                         DamageIndicatorEventSnapshot eventSnapshot) {
        if (!listening || !plugin.cfg().indicators().enabled() || snapshot != activeSettings) {
            return;
        }
        double after = living.getHealth();
        double amount = eventSnapshot.damage() ? before - after : after - before;
        DamageIndicatorSettingsDoc document = snapshot.document();
        if (amount <= document.limits().minimumDelta()) {
            return;
        }
        Map<String, Object> values = eventSnapshot.values(living, plugin, amount);
        GlossConditionContext context = new GlossConditionContext(
            null, living, null, living.getLocation(), values);
        DamageIndicatorSettingsDoc.IndicatorPresentation presentation = snapshot.conditions().select(
            eventSnapshot.damage(), new GlossConditionScope(plugin, context), conditionErrors);
        if (presentation == null) {
            return;
        }
        int limit = document.limits().maxPerSecond();
        if (!rateLimiter.tryAcquire(limit)) {
            return;
        }
        budget.record(nowMs(), limit);
        spawn(living, amount, eventSnapshot.damage(), snapshot, presentation, values);
    }

    private void spawn(LivingEntity target, double amount, boolean damage,
                       ActiveSettings snapshot,
                       DamageIndicatorSettingsDoc.IndicatorPresentation presentation,
                       Map<String, Object> eventValues) {
        long spawnEpoch = lifecycleEpoch.get();
        if (!listening) {
            return;
        }
        DamageIndicatorSettingsDoc.Limits limits = snapshot.document().limits();
        AdmissionBudget.Lease admission = admissions.tryAcquire(
            liveLimit(limits.maxPerSecond(), limits.lifetimeMs()));
        if (admission == null) {
            return;
        }
        TemporaryHologram hologram = null;
        LiveIndicator candidate = null;
        String id = null;
        boolean retained = false;
        try {
            Location anchor = target.getLocation();
            Vector offset = presentation.offset();
            DamageIndicatorSettingsDoc.Motion motion = presentation.motion();
            DamageIndicatorSettingsDoc.Transform transform = presentation.transform();
            double angleRadians = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0D);
            double lifetimeSeconds = limits.lifetimeMs() / 1000.0D;
            long startedNanos = System.nanoTime();
            DamageIndicatorTrajectory.Frame initialFrame = DamageIndicatorTrajectory.sample(
                offset, motion, transform, angleRadians, 0.0D, lifetimeSeconds);
            Location initial = offsetFrom(anchor, initialFrame.x(), initialFrame.y(), initialFrame.z());
            id = (damage ? "dmg-" : "heal-") + target.getUniqueId() + "-"
                + M.ms() + "-" + sequence.incrementAndGet();
            hologram = plugin.holograms().createTemporary(id, initial, limits.lifetimeMs());
            hologram.setParticleLayers(presentation.particleLayers());
            String formatted = IndicatorTextFormat.format(amount, limits.decimals());
            hologram.addLine(presentation.format().replace("{amount}", formatted));
            hologram.viewers().whitelist();

            hologram.bindPosition(target, () -> {
                DamageIndicatorTrajectory.Frame frame = DamageIndicatorTrajectory.sample(
                    offset, motion, transform, angleRadians,
                    elapsedSeconds(startedNanos), lifetimeSeconds);
                return offsetFrom(anchor, frame.x(), frame.y(), frame.z());
            });
            hologram.bindPresentation(target, () -> {
                DamageIndicatorTrajectory.Frame frame = DamageIndicatorTrajectory.sample(
                    offset, motion, transform, angleRadians,
                    elapsedSeconds(startedNanos), lifetimeSeconds);
                return new HologramPresentation(
                    frame.scale(), frame.scale(), frame.scale(),
                    0.0D, 0.0D, frame.spinDegrees(), frame.opacity());
            });

            candidate = new LiveIndicator(
                hologram,
                nowMs() + limits.lifetimeMs(),
                admission,
                snapshot.conditions(),
                eventValues,
                anchor,
                plugin.cfg().holograms().viewRange());
            live.put(id, candidate);
            if (!spawnStillCurrent(
                listening, lifecycleEpoch.get(), spawnEpoch, plugin.cfg().indicators().enabled())) {
                return;
            }
            retained = true;
            scheduleAudience(candidate);
            GlossTelemetry.countIndicatorSpawn();
        } catch (RuntimeException failure) {
            Gloss.logExceptionStackThrottled(false, "damage-indicator-create", failure,
                "Could not create a damage indicator for entity %s.", target.getUniqueId());
        } finally {
            if (!retained) {
                if (candidate != null) {
                    if (id != null) {
                        live.remove(id, candidate);
                    }
                    candidate.retire(true);
                } else {
                    destroyFailedSpawn(hologram);
                    admission.close();
                }
            }
        }
    }

    private void destroyAll() {
        int failures = 0;
        for (Map.Entry<String, LiveIndicator> entry : live.entrySet()) {
            LiveIndicator indicator = entry.getValue();
            if (live.remove(entry.getKey(), indicator) && !indicator.retire(true)) {
                failures++;
            }
        }
        if (failures > 0) {
            Gloss.warn("Failed to destroy " + failures + " damage indicators on shutdown.");
        }
    }

    private void scheduleAudience(LiveIndicator indicator) {
        plugin.holograms().forEachNearbyViewer(
            indicator.anchor, indicator.rangeSquared, viewer -> {
                FoliaScheduler.runEntity(plugin, viewer, () -> {
                    if (listening && viewer.isOnline()) {
                        indicator.updateViewer(viewer);
                    }
                });
            });
    }

    private void reevaluateViewer(Player viewer) {
        if (!listening || !viewer.isOnline()) {
            return;
        }
        for (LiveIndicator indicator : live.values()) {
            indicator.updateViewer(viewer);
        }
    }

    private static boolean sameChunk(Location first, Location second) {
        return first.getWorld() == second.getWorld()
            && (first.getBlockX() >> 4) == (second.getBlockX() >> 4)
            && (first.getBlockZ() >> 4) == (second.getBlockZ() >> 4);
    }

    static int liveLimit(int maxPerSecond, long maxMsAlive) {
        long rate = Math.max(1L, maxPerSecond);
        long lifetime = Math.max(1L, maxMsAlive);
        long expected = (rate * lifetime + 999L) / 1000L;
        return (int) Math.min(MAX_LIVE_INDICATORS, Math.max(1L, expected));
    }

    static boolean spawnStillCurrent(boolean listening, long currentEpoch, long spawnEpoch, boolean enabled) {
        return listening && enabled && currentEpoch == spawnEpoch;
    }

    static Location offsetFrom(Location origin, double x, double y, double z) {
        return origin.clone().add(x, y, z);
    }

    private static void destroyFailedSpawn(TemporaryHologram hologram) {
        if (hologram == null) {
            return;
        }
        try {
            hologram.destroy();
        } catch (Throwable failure) {
            Gloss.logExceptionStackThrottled(false, "damage-indicator-cleanup", failure,
                "Could not clean up a failed damage indicator spawn.");
        }
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    private static double elapsedSeconds(long startedNanos) {
        return Math.max(0L, System.nanoTime() - startedNanos) / 1_000_000_000.0D;
    }

    private record ActiveSettings(DamageIndicatorSettingsDoc document,
                                  DamageIndicatorConditionPlan conditions) {
        private static ActiveSettings defaults() {
            DamageIndicatorSettingsDoc document = DamageIndicatorSettingsDoc.DEFAULTS;
            return new ActiveSettings(document, DamageIndicatorConditionPlan.compile(document));
        }
    }

    private final class LiveIndicator {
        private final TemporaryHologram hologram;
        private final long expiresAtMs;
        private final AdmissionBudget.Lease admission;
        private final DamageIndicatorConditionPlan conditions;
        private final Map<String, Object> eventValues;
        private final Location anchor;
        private final double rangeSquared;
        private final AtomicBoolean retired = new AtomicBoolean();

        private LiveIndicator(TemporaryHologram hologram, long expiresAtMs,
                              AdmissionBudget.Lease admission,
                              DamageIndicatorConditionPlan conditions,
                              Map<String, Object> eventValues,
                              Location anchor,
                              double viewRange) {
            this.hologram = hologram;
            this.expiresAtMs = expiresAtMs;
            this.admission = admission;
            this.conditions = conditions;
            this.eventValues = Map.copyOf(eventValues);
            this.anchor = anchor.clone();
            this.rangeSquared = viewRange * viewRange;
        }

        private void updateViewer(Player viewer) {
            if (retired.get()) {
                return;
            }
            Location viewerLocation = viewer.getLocation();
            if (viewerLocation.getWorld() != anchor.getWorld()
                || viewerLocation.distanceSquared(anchor) > rangeSquared) {
                hologram.viewers().remove(viewer.getUniqueId());
                return;
            }
            GlossConditionContext context = new GlossConditionContext(
                viewer, null, null, viewer.getLocation(), eventValues);
            boolean included = conditions.includesViewer(
                new GlossConditionScope(plugin, context), conditionErrors);
            if (included) {
                hologram.viewers().add(viewer.getUniqueId());
            } else {
                hologram.viewers().remove(viewer.getUniqueId());
            }
        }

        private boolean retire(boolean destroy) {
            if (!retired.compareAndSet(false, true)) {
                return true;
            }
            try {
                if (destroy) {
                    hologram.destroy();
                }
                return true;
            } catch (Throwable failure) {
                Gloss.logExceptionStackThrottled(false, "damage-indicator-destroy", failure,
                    "Could not destroy a damage indicator.");
                return false;
            } finally {
                admission.close();
            }
        }
    }

}
