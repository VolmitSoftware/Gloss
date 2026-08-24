package art.arcane.gloss.indicator;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.api.TemporaryHologram;
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
import org.bukkit.event.player.PlayerQuitEvent;
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
import java.util.function.Consumer;

public final class DamageIndicatorsService implements Listener {
    private static final String SHOW_PERMISSION = "gloss.indicators.show";
    private static final long DEBOUNCE_MS = 150L;
    private static final long SAMPLE_DELAY_TICKS = 2L;
    private static final int DRIVER_INTERVAL_TICKS = 2;
    static final int PERMISSION_REFRESHES_PER_DRIVER = 16;
    private static final long BUDGET_WINDOW_MS = 1000L;
    private static final long PERMISSION_REFRESH_INTERVAL_MS = 5000L;
    static final int MAX_LIVE_INDICATORS = 2048;

    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<DamageIndicatorSettingsDoc> settings;
    private final Map<UUID, Long> debounce = new ConcurrentHashMap<>();
    private final Map<UUID, Player> permissionViewers = new ConcurrentHashMap<>();
    private final IndicatorPermissionCache viewerPermissions = new IndicatorPermissionCache();
    private final SlidingWindowRateLimiter rateLimiter = new SlidingWindowRateLimiter();
    private final Map<String, LiveIndicator> live = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong lifecycleEpoch = new AtomicLong();
    private final IndicatorBudget budget = new IndicatorBudget(BUDGET_WINDOW_MS);
    private final AdmissionBudget admissions = new AdmissionBudget(MAX_LIVE_INDICATORS);

    private volatile DamageIndicatorSettingsDoc activeSettings = DamageIndicatorSettingsDoc.DEFAULTS;
    private int driverTaskId = -1;
    private volatile boolean started;
    private volatile boolean listening;

    public DamageIndicatorsService(Gloss plugin) {
        this.plugin = plugin;
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
        permissionViewers.clear();
        viewerPermissions.clear();
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
        DamageIndicatorSettingsDoc snapshot = activeSettings;
        if (snapshot.damage().enabled()) {
            sample(event.getEntity(), snapshot);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        DamageIndicatorSettingsDoc snapshot = activeSettings;
        if (snapshot.healing().enabled()) {
            sample(event.getEntity(), snapshot);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onViewerJoin(PlayerJoinEvent event) {
        trackViewer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onViewerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        permissionViewers.remove(playerId);
        viewerPermissions.remove(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onViewerChangedWorld(PlayerChangedWorldEvent event) {
        scheduleViewerPermissionRefresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onViewerRespawn(PlayerRespawnEvent event) {
        scheduleViewerPermissionRefresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onViewerMove(PlayerMoveEvent event) {
        Location destination = event.getTo();
        if (destination == null || sameChunk(event.getFrom(), destination)) {
            return;
        }
        cacheViewerPermission(event.getPlayer());
    }

    private void register() {
        if (listening) {
            return;
        }
        lifecycleEpoch.incrementAndGet();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        listening = true;
        driverTaskId = plugin.scheduler().ar(this::drive, DRIVER_INTERVAL_TICKS);
        seedViewerPermissions();
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
            } else {
                seedViewerPermissions();
            }
            return;
        }
        unregister();
        destroyAll();
        debounce.clear();
        permissionViewers.clear();
        viewerPermissions.clear();
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
        DamageIndicatorSettingsDoc previous = activeSettings;
        activeSettings = updated;
        if (listening && !previous.equals(updated)) {
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
            }
        }
        refreshViewerPermissionCohort();
    }

    private void sample(Entity entity, DamageIndicatorSettingsDoc snapshot) {
        GlossConfig.Indicators cfg = plugin.cfg().indicators();
        if (!cfg.enabled()) {
            return;
        }
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        if (snapshot.filters().disabledWorlds().contains(living.getWorld().getName())) {
            return;
        }
        if (permissionViewers.isEmpty()) {
            return;
        }
        long now = nowMs();
        if (budget.saturated(now, snapshot.limits().maxPerSecond())) {
            return;
        }
        if (!claimDebounce(debounce, living.getUniqueId(), now, DEBOUNCE_MS)) {
            return;
        }

        double before = living.getHealth();
        FoliaScheduler.runEntity(plugin, living, () -> compare(living, before, snapshot), SAMPLE_DELAY_TICKS);
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

    private void compare(LivingEntity living, double before, DamageIndicatorSettingsDoc snapshot) {
        if (!listening || !plugin.cfg().indicators().enabled() || snapshot != activeSettings) {
            return;
        }
        double after = living.getHealth();
        double delta = after - before;
        if (Math.abs(delta) <= snapshot.limits().minimumDelta()) {
            return;
        }
        boolean damage = delta < 0.0D;
        DamageIndicatorSettingsDoc.Style style = damage ? snapshot.damage() : snapshot.healing();
        if (!style.enabled()) {
            return;
        }
        int limit = snapshot.limits().maxPerSecond();
        if (!rateLimiter.tryAcquire(limit)) {
            return;
        }
        budget.record(nowMs(), limit);
        spawn(living, Math.abs(delta), damage, snapshot, style);
    }

    private void spawn(LivingEntity target, double amount, boolean damage,
                       DamageIndicatorSettingsDoc snapshot, DamageIndicatorSettingsDoc.Style style) {
        long spawnEpoch = lifecycleEpoch.get();
        if (!listening) {
            return;
        }
        DamageIndicatorSettingsDoc.Limits limits = snapshot.limits();
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
            Vector offset = style.offset();
            DamageIndicatorSettingsDoc.Motion motion = style.motion();
            DamageIndicatorSettingsDoc.Presentation presentation = style.presentation();
            double angleRadians = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0D);
            double lifetimeSeconds = limits.lifetimeMs() / 1000.0D;
            long startedNanos = System.nanoTime();
            DamageIndicatorTrajectory.Frame initialFrame = DamageIndicatorTrajectory.sample(
                offset, motion, presentation, angleRadians, 0.0D, lifetimeSeconds);
            Location initial = offsetFrom(anchor, initialFrame.x(), initialFrame.y(), initialFrame.z());
            id = (damage ? "dmg-" : "heal-") + target.getUniqueId() + "-"
                + M.ms() + "-" + sequence.incrementAndGet();
            hologram = plugin.holograms().createTemporary(id, initial, limits.lifetimeMs());
            String formatted = IndicatorTextFormat.format(amount, limits.decimals());
            hologram.addLine(style.format().replace("{amount}", formatted));
            hideFromUnpermitted(target, hologram);

            hologram.bindPosition(target, () -> {
                DamageIndicatorTrajectory.Frame frame = DamageIndicatorTrajectory.sample(
                    offset, motion, presentation, angleRadians,
                    elapsedSeconds(startedNanos), lifetimeSeconds);
                return offsetFrom(anchor, frame.x(), frame.y(), frame.z());
            });
            hologram.bindPresentation(target, () -> {
                DamageIndicatorTrajectory.Frame frame = DamageIndicatorTrajectory.sample(
                    offset, motion, presentation, angleRadians,
                    elapsedSeconds(startedNanos), lifetimeSeconds);
                return new HologramPresentation(
                    frame.scale(), frame.scale(), frame.scale(),
                    0.0D, 0.0D, frame.spinDegrees(), frame.opacity());
            });

            candidate = new LiveIndicator(hologram, nowMs() + limits.lifetimeMs(), admission);
            live.put(id, candidate);
            if (!spawnStillCurrent(
                listening, lifecycleEpoch.get(), spawnEpoch, plugin.cfg().indicators().enabled())) {
                return;
            }
            retained = true;
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

    private void hideFromUnpermitted(LivingEntity target, TemporaryHologram hologram) {
        double range = plugin.cfg().holograms().viewRange();
        double rangeSquared = range * range;
        Location anchor = target.getLocation();
        Consumer<Player> hide = viewer -> {
            if (!viewerPermissions.allowed(viewer.getUniqueId())) {
                hologram.viewers().add(viewer.getUniqueId());
            }
        };
        plugin.holograms().forEachNearbyViewer(anchor, rangeSquared, hide);
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

    private void seedViewerPermissions() {
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            UUID playerId = viewer.getUniqueId();
            permissionViewers.put(playerId, viewer);
            if (!viewerPermissions.track(playerId)) {
                viewerPermissions.makeDue(playerId);
            }
        }
    }

    private void trackViewer(Player viewer) {
        UUID playerId = viewer.getUniqueId();
        permissionViewers.put(playerId, viewer);
        viewerPermissions.track(playerId);
        scheduleViewerPermissionRefresh(viewer);
    }

    private void refreshViewerPermissionCohort() {
        long now = nowMs();
        for (int index = 0; index < PERMISSION_REFRESHES_PER_DRIVER; index++) {
            UUID playerId = viewerPermissions.claimNextRefresh(now, PERMISSION_REFRESH_INTERVAL_MS);
            if (playerId == null) {
                return;
            }
            Player viewer = permissionViewers.get(playerId);
            if (viewer != null) {
                scheduleClaimedViewerPermissionRefresh(viewer);
            }
        }
    }

    private void scheduleViewerPermissionRefresh(Player viewer) {
        UUID playerId = viewer.getUniqueId();
        viewerPermissions.defer(playerId, nowMs(), PERMISSION_REFRESH_INTERVAL_MS);
        scheduleClaimedViewerPermissionRefresh(viewer);
    }

    private void scheduleClaimedViewerPermissionRefresh(Player viewer) {
        UUID playerId = viewer.getUniqueId();
        boolean accepted = FoliaScheduler.runEntity(plugin, viewer, () -> {
            if (listening && viewer.isOnline()) {
                cacheViewerPermission(viewer);
            }
        });
        if (!accepted) {
            viewerPermissions.makeDue(playerId);
        }
    }

    private void cacheViewerPermission(Player viewer) {
        viewerPermissions.update(viewer.getUniqueId(), viewer.hasPermission(SHOW_PERMISSION));
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

    private static final class LiveIndicator {
        private final TemporaryHologram hologram;
        private final long expiresAtMs;
        private final AdmissionBudget.Lease admission;
        private final AtomicBoolean retired = new AtomicBoolean();

        private LiveIndicator(TemporaryHologram hologram, long expiresAtMs, AdmissionBudget.Lease admission) {
            this.hologram = hologram;
            this.expiresAtMs = expiresAtMs;
            this.admission = admission;
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
