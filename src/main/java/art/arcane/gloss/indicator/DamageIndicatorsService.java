package art.arcane.gloss.indicator;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.service.AdmissionBudget;
import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
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

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class DamageIndicatorsService implements Listener {
    private static final String SHOW_PERMISSION = "gloss.indicators.show";
    private static final double MINIMUM_DELTA = 0.009D;
    private static final long DEBOUNCE_MS = 150L;
    private static final long SAMPLE_DELAY_TICKS = 2L;
    private static final int DRIVER_INTERVAL_TICKS = 2;
    static final int PERMISSION_REFRESHES_PER_DRIVER = 16;
    private static final long BUDGET_WINDOW_MS = 1000L;
    private static final long PERMISSION_REFRESH_INTERVAL_MS = 5000L;
    private static final double HEAL_LIFT_DIVISOR = 19.5D;
    static final int MAX_LIVE_INDICATORS = 2048;

    private final Gloss plugin;
    private final Map<UUID, Long> debounce = new ConcurrentHashMap<>();
    private final Map<UUID, Player> permissionViewers = new ConcurrentHashMap<>();
    private final IndicatorPermissionCache viewerPermissions = new IndicatorPermissionCache();
    private final SlidingWindowRateLimiter rateLimiter = new SlidingWindowRateLimiter();
    private final Map<String, LiveIndicator> live = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong lifecycleEpoch = new AtomicLong();
    private final IndicatorBudget budget = new IndicatorBudget(BUDGET_WINDOW_MS);
    private final AdmissionBudget admissions = new AdmissionBudget(MAX_LIVE_INDICATORS);

    private int driverTaskId = -1;
    private volatile boolean listening;

    public DamageIndicatorsService(Gloss plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        if (!plugin.cfg().indicators().enabled()) {
            return;
        }
        register();
    }

    public void disable() {
        unregister();
        destroyAll();
        debounce.clear();
        permissionViewers.clear();
        viewerPermissions.clear();
    }

    public void reload() {
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

    public int activeCount() {
        return admissions.active();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        sample(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        if (!plugin.cfg().indicators().showHeals()) {
            return;
        }
        sample(event.getEntity());
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

    private void sample(Entity entity) {
        GlossConfig.Indicators cfg = plugin.cfg().indicators();
        if (!cfg.enabled()) {
            return;
        }
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        if (permissionViewers.isEmpty()) {
            return;
        }
        long now = nowMs();
        if (budget.saturated(now, cfg.maxPerSecond())) {
            return;
        }
        if (!claimDebounce(debounce, living.getUniqueId(), now, DEBOUNCE_MS)) {
            return;
        }

        double before = living.getHealth();
        FoliaScheduler.runEntity(plugin, living, () -> compare(living, before), SAMPLE_DELAY_TICKS);
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

    private void compare(LivingEntity living, double before) {
        if (!listening || !plugin.cfg().indicators().enabled()) {
            return;
        }
        double after = living.getHealth();
        double delta = after - before;
        if (Math.abs(delta) <= MINIMUM_DELTA) {
            return;
        }
        int limit = plugin.cfg().indicators().maxPerSecond();
        if (!rateLimiter.tryAcquire(limit)) {
            return;
        }
        budget.record(nowMs(), limit);
        spawn(living, Math.abs(delta), delta < 0.0D);
    }

    private void spawn(LivingEntity target, double amount, boolean damage) {
        GlossConfig.Indicators cfg = plugin.cfg().indicators();
        long spawnEpoch = lifecycleEpoch.get();
        if (!listening) {
            return;
        }
        AdmissionBudget.Lease admission = admissions.tryAcquire(liveLimit(cfg.maxPerSecond(), cfg.maxMsAlive()));
        if (admission == null) {
            return;
        }
        TemporaryHologram hologram = null;
        LiveIndicator candidate = null;
        String id = null;
        boolean retained = false;
        try {
            Location initial = target.getLocation().add(0.0D, damage ? 0.7D : -0.1D, 0.0D);
            id = (damage ? "dmg-" : "heal-") + target.getUniqueId() + "-"
                + M.ms() + "-" + sequence.incrementAndGet();
            hologram = plugin.holograms().createTemporary(id, initial.clone(), cfg.maxMsAlive());
            String prefix = damage ? cfg.damagePrefix() : cfg.healPrefix();
            hologram.addLine(prefix + IndicatorTextFormat.format(amount, cfg.decimals()));
            hideFromUnpermitted(target, hologram);

            IndicatorMotion motion = IndicatorMotion.scatter(cfg.randomThrowForce(), cfg.initialUpForce());
            double step = damage ? -cfg.gravityForce() : cfg.gravityForce() / HEAL_LIFT_DIVISOR;
            hologram.bindPosition(target, () -> {
                motion.y += step;
                return offsetFrom(initial, motion.x, motion.y, motion.z);
            });

            candidate = new LiveIndicator(hologram, nowMs() + cfg.maxMsAlive(), admission);
            live.put(id, candidate);
            if (!spawnStillCurrent(
                listening, lifecycleEpoch.get(), spawnEpoch, plugin.cfg().indicators().enabled())) {
                return;
            }
            retained = true;
            GlossTelemetry.countIndicatorSpawn();
        } catch (RuntimeException failure) {
            Gloss.logExceptionStack(false, failure,
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
            Gloss.logExceptionStack(false, failure, "Could not clean up a failed damage indicator spawn.");
        }
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
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
                Gloss.logExceptionStack(false, failure, "Could not destroy a damage indicator.");
                return false;
            } finally {
                admission.close();
            }
        }
    }

    private static final class IndicatorMotion {
        private double x;
        private double y;
        private double z;

        private static IndicatorMotion scatter(double throwForce, double upForce) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            IndicatorMotion motion = new IndicatorMotion();
            motion.x = (random.nextDouble() - random.nextDouble()) * throwForce;
            motion.y = upForce;
            motion.z = (random.nextDouble() - random.nextDouble()) * throwForce;
            return motion;
        }
    }
}
