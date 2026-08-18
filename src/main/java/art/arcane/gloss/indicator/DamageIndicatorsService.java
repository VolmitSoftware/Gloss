package art.arcane.gloss.indicator;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.util.Ticks;
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

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public final class DamageIndicatorsService implements Listener {
    private static final String SHOW_PERMISSION = "gloss.indicators.show";
    private static final double MINIMUM_DELTA = 0.009D;
    private static final int DEBOUNCE_TICKS = 3;
    private static final long SAMPLE_DELAY_TICKS = 2L;
    private static final double HEAL_LIFT_DIVISOR = 19.5D;

    private final Gloss plugin;
    private final Set<UUID> debounce = ConcurrentHashMap.newKeySet();
    private final SlidingWindowRateLimiter rateLimiter = new SlidingWindowRateLimiter();
    private final Map<String, TemporaryHologram> live = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

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

    private void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        listening = true;
    }

    private void unregister() {
        if (!listening) {
            return;
        }
        HandlerList.unregisterAll(this);
        listening = false;
    }

    private void sample(Entity entity) {
        if (!plugin.cfg().indicators().enabled()) {
            return;
        }
        if (!(entity instanceof LivingEntity living)) {
            return;
        }

        UUID entityId = living.getUniqueId();
        if (!debounce.add(entityId)) {
            return;
        }
        plugin.scheduler().a(() -> debounce.remove(entityId), DEBOUNCE_TICKS);

        double before = living.getHealth();
        FoliaScheduler.runEntity(plugin, living, () -> compare(living, before), SAMPLE_DELAY_TICKS);
    }

    private void compare(LivingEntity living, double before) {
        double after = living.getHealth();
        double delta = after - before;
        if (Math.abs(delta) <= MINIMUM_DELTA) {
            return;
        }
        if (!rateLimiter.tryAcquire(M.ms(), plugin.cfg().indicators().maxPerSecond())) {
            return;
        }
        spawn(living, Math.abs(delta), delta < 0.0D);
    }

    private void spawn(LivingEntity target, double amount, boolean damage) {
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

        live.put(id, hologram);
        plugin.scheduler().a(() -> live.remove(id), Ticks.fromMs(cfg.maxMsAlive()));
    }

    private void hideFromUnpermitted(LivingEntity target, TemporaryHologram hologram) {
        World world = target.getWorld();
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (viewer.getWorld() != world) {
                continue;
            }
            if (viewer.hasPermission(SHOW_PERMISSION)) {
                continue;
            }
            hologram.viewers().add(viewer.getUniqueId());
        }
    }

    private void destroyAll() {
        int failures = 0;
        for (TemporaryHologram hologram : live.values()) {
            try {
                hologram.destroy();
            } catch (Throwable failure) {
                failures++;
            }
        }
        live.clear();
        if (failures > 0) {
            Gloss.warn("Failed to destroy " + failures + " damage indicators on shutdown.");
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
