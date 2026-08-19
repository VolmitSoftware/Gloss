package art.arcane.gloss.indicator;

import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SlidingWindowRateLimiter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class DamageIndicatorsService implements Listener {
    private static final String SHOW_PERMISSION = "gloss.indicators.show";
    private static final double MINIMUM_DELTA = 0.009D;
    private static final long DEBOUNCE_MS = 150L;
    private static final long SAMPLE_DELAY_TICKS = 2L;
    private static final int DRIVER_INTERVAL_TICKS = 2;
    private static final long BUDGET_WINDOW_MS = 1000L;
    private static final long PERMISSION_SCAN_INTERVAL_MS = 1000L;
    private static final double HEAL_LIFT_DIVISOR = 19.5D;

    private final Gloss plugin;
    private final Map<UUID, Long> debounce = new ConcurrentHashMap<>();
    private final SlidingWindowRateLimiter rateLimiter = new SlidingWindowRateLimiter();
    private final Map<String, LiveIndicator> live = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final IndicatorBudget budget = new IndicatorBudget(BUDGET_WINDOW_MS);

    private volatile boolean permissionScanValid;
    private volatile long permissionScanAt;
    private volatile boolean anyoneLacksShow = true;
    private int driverTaskId = -1;
    private boolean listening;

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
    }

    public void reload() {
        permissionScanValid = false;
        if (plugin.cfg().indicators().enabled()) {
            if (!listening) {
                register();
            }
            return;
        }
        unregister();
        destroyAll();
    }

    public int activeCount() {
        return live.size();
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
    public void onJoin(PlayerJoinEvent event) {
        permissionScanValid = false;
    }

    private void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        listening = true;
        driverTaskId = plugin.scheduler().ar(this::drive, DRIVER_INTERVAL_TICKS);
    }

    private void unregister() {
        if (!listening) {
            return;
        }
        if (driverTaskId != -1) {
            plugin.scheduler().car(driverTaskId);
            driverTaskId = -1;
        }
        HandlerList.unregisterAll(this);
        listening = false;
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
            if (indicators.next().getValue().expiresAtMs() <= now) {
                indicators.remove();
            }
        }
    }

    private void sample(Entity entity) {
        GlossConfig.Indicators cfg = plugin.cfg().indicators();
        if (!cfg.enabled()) {
            return;
        }
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        if (plugin.getServer().getOnlinePlayers().isEmpty()) {
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
        GlossTelemetry.countIndicatorSpawn();
        GlossConfig.Indicators cfg = plugin.cfg().indicators();
        Location initial = target.getLocation().add(0.0D, damage ? 0.7D : -0.1D, 0.0D);
        String id = (damage ? "dmg-" : "heal-") + target.getUniqueId() + "-" + M.ms() + "-" + sequence.incrementAndGet();
        TemporaryHologram hologram = plugin.holograms().createTemporary(id, initial.clone(), cfg.maxMsAlive());
        String prefix = damage ? cfg.damagePrefix() : cfg.healPrefix();
        hologram.addLine(prefix + IndicatorTextFormat.format(amount, cfg.decimals()));
        hideFromUnpermitted(target, hologram);

        IndicatorMotion motion = IndicatorMotion.scatter(cfg.randomThrowForce(), cfg.initialUpForce());
        double step = damage ? -cfg.gravityForce() : cfg.gravityForce() / HEAL_LIFT_DIVISOR;
        hologram.bindPosition(() -> {
            motion.y += step;
            return initial.add(motion.x, motion.y, motion.z);
        });

        live.put(id, new LiveIndicator(hologram, nowMs() + cfg.maxMsAlive()));
    }

    /**
     * The permission gate stays in front: on a server where everyone holds
     * {@code gloss.indicators.show} this does nothing at all. Past it, the proximity scan reads the
     * hologram drive pass's per-world position snapshot rather than allocating a fresh
     * {@code world.getPlayers()} list per damage event. Positions there are at most one drive
     * interval old — far fresher than the permission cache already gating this call — and when no
     * pass has captured the world yet the original scan runs instead.
     */
    private void hideFromUnpermitted(LivingEntity target, TemporaryHologram hologram) {
        if (!anyoneLacksShowPermission()) {
            return;
        }

        double range = plugin.cfg().holograms().viewRange();
        double rangeSquared = range * range;
        Location anchor = target.getLocation();
        Consumer<Player> hide = viewer -> {
            if (viewer.hasPermission(SHOW_PERMISSION)) {
                return;
            }
            hologram.viewers().add(viewer.getUniqueId());
        };
        if (plugin.holograms().forEachNearbyViewer(anchor, rangeSquared, hide)) {
            return;
        }

        World world = target.getWorld();
        Location scratch = new Location(world, 0.0D, 0.0D, 0.0D);
        for (Player viewer : world.getPlayers()) {
            if (viewer.getLocation(scratch).distanceSquared(anchor) > rangeSquared) {
                continue;
            }
            hide.accept(viewer);
        }
    }

    private boolean anyoneLacksShowPermission() {
        long now = nowMs();
        if (permissionScanValid && now - permissionScanAt < PERMISSION_SCAN_INTERVAL_MS) {
            return anyoneLacksShow;
        }

        boolean lacking = false;
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (!viewer.hasPermission(SHOW_PERMISSION)) {
                lacking = true;
                break;
            }
        }
        anyoneLacksShow = lacking;
        permissionScanAt = now;
        permissionScanValid = true;
        return lacking;
    }

    private void destroyAll() {
        int failures = 0;
        for (LiveIndicator indicator : live.values()) {
            try {
                indicator.hologram().destroy();
            } catch (Throwable failure) {
                failures++;
            }
        }
        live.clear();
        if (failures > 0) {
            Gloss.warn("Failed to destroy " + failures + " damage indicators on shutdown.");
        }
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    private record LiveIndicator(TemporaryHologram hologram, long expiresAtMs) {
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
