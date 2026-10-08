package art.arcane.gloss.particle;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.ParticleLayer;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.volmlib.util.bukkit.registry.RegistryUtil;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class ParticleService {
    private static final long TICK_MILLIS = 50L;
    private static final int MAX_SAMPLE_CACHE_ENTRIES = 2048;

    private record ResolvedParticle(Particle particle, Object data) {
    }

    private record SampleKey(ParticleLayer.Geometry geometry, List<ParticleRect> targets, int limit) {
    }

    static final class Budget {
        private long tick = Long.MIN_VALUE;
        private int used;

        private synchronized int reserve(long currentTick, int requested, int limit) {
            if (currentTick < tick || requested <= 0) {
                return 0;
            }
            if (currentTick > tick) {
                tick = currentTick;
                used = 0;
            }
            int admitted = Math.min(requested, Math.max(0, limit - used));
            used += admitted;
            return admitted;
        }

        private synchronized void release(long currentTick, int amount) {
            if (currentTick == tick && amount > 0) {
                used = Math.max(0, used - amount);
            }
        }
    }

    private final Gloss plugin;
    private final BoundedCache<ParticleLayer.ParticleSpec, ResolvedParticle> particles;
    private final BoundedCache<SampleKey, List<Vector>> samples;
    private final BoundedCache<String, ShowCondition> conditions;
    private final Map<UUID, Budget> viewerBudgets;
    private final Map<UUID, Map<Object, Cadence>> viewerCadences;
    private final Budget globalBudget;

    public ParticleService(Gloss plugin) {
        this.plugin = plugin;
        this.particles = new BoundedCache<>(MAX_SAMPLE_CACHE_ENTRIES);
        this.samples = new BoundedCache<>(MAX_SAMPLE_CACHE_ENTRIES);
        this.conditions = new BoundedCache<>(MAX_SAMPLE_CACHE_ENTRIES);
        this.viewerBudgets = new ConcurrentHashMap<>();
        this.viewerCadences = new ConcurrentHashMap<>();
        this.globalBudget = new Budget();
    }

    public boolean isDue(Player viewer, Object source, ParticleLayer layer, long tick) {
        return cadence(viewer.getUniqueId(), source).isDue(layer, tick);
    }

    public void emit(Player viewer, Object source, ParticleFrame frame, ParticleLayer layer,
                     List<ParticleRect> targets, long tick) {
        if (!plugin.cfg().particles().enabled() || !viewer.isOnline() || !conditions.get(layer.show(), ShowCondition::of).matches(plugin, viewer)) {
            return;
        }
        Location origin = frame.origin();
        Location viewerLocation = viewer.getLocation();
        if (origin.getWorld() != viewerLocation.getWorld()
            || origin.distanceSquared(viewerLocation) > square(layer.viewDistance())) {
            return;
        }
        Cadence cadence = cadence(viewer.getUniqueId(), source);
        if (!cadence.isDue(layer, tick)) {
            return;
        }
        int cachedLimit = plugin.cfg().particles().maxCachedSamplesPerLayer();
        List<ParticleRect> stableTargets = targets == null ? List.of() : List.copyOf(targets);
        List<Vector> sampled = samples.get(new SampleKey(layer.geometry(), stableTargets, cachedLimit),
            key -> ParticleGeometrySampler.sample(key.geometry(), key.targets(), key.limit()));
        List<Vector> selected = select(sampled, layer.emission(), tick);
        int count = layer.particle().count();
        int admitted = reserve(viewer.getUniqueId(), selected.size() * count);
        if (admitted == 0) {
            return;
        }
        ResolvedParticle resolved = particles.get(layer.particle(), this::resolve);
        Vector spread = layer.particle().spread();
        for (int index = 0; index * count < admitted; index++) {
            Location point = frame.world(selected.get(index), layer.placement());
            viewer.spawnParticle(resolved.particle(), point, Math.min(count, admitted - index * count),
                spread.getX(), spread.getY(), spread.getZ(), layer.particle().speed(), resolved.data());
        }
        cadence.emitted(layer, tick);
    }

    /**
     * Emits a {@code world} scope layer for one viewer. Anchors are absolute, so nothing here goes
     * through a projection frame; the viewer budget is the same one every other particle spends.
     */
    public void renderWorld(Player viewer, ParticleLayer layer,
                            ParticleGeometrySampler.AnchorResolver resolver, long tick) {
        if (!plugin.cfg().particles().enabled() || !viewer.isOnline() || !conditions.get(layer.show(), ShowCondition::of).matches(plugin, viewer)
            || tick % layer.emission().intervalTicks() != 0L) {
            return;
        }
        List<Vector> sampled = ParticleGeometrySampler.sampleWorld(layer.geometry(), resolver,
            plugin.cfg().particles().maxCachedSamplesPerLayer());
        if (sampled.isEmpty()) {
            return;
        }
        List<Vector> selected = select(sampled, layer.emission(), tick);
        int count = layer.particle().count();
        int admitted = reserve(viewer.getUniqueId(), selected.size() * count);
        if (admitted == 0) {
            return;
        }
        ResolvedParticle resolved = particles.get(layer.particle(), this::resolve);
        Location viewerLocation = viewer.getLocation();
        double rangeSquared = square(layer.viewDistance());
        Vector spread = layer.particle().spread();
        for (int index = 0; index * count < admitted; index++) {
            Vector point = selected.get(index);
            Location at = new Location(viewerLocation.getWorld(), point.getX(), point.getY(), point.getZ());
            if (at.distanceSquared(viewerLocation) > rangeSquared) {
                continue;
            }
            viewer.spawnParticle(resolved.particle(), at, Math.min(count, admitted - index * count),
                spread.getX(), spread.getY(), spread.getZ(), layer.particle().speed(), resolved.data());
        }
    }

    public void prune(UUID playerId) {
        viewerBudgets.remove(playerId);
        viewerCadences.remove(playerId);
    }

    public void clear() {
        ViewerParticles.clear();
        particles.clear();
        samples.clear();
        conditions.clear();
        viewerBudgets.clear();
        viewerCadences.clear();
        ParticleTextLayout.clearCaches();
    }

    private Cadence cadence(UUID playerId, Object source) {
        Objects.requireNonNull(source);
        return viewerCadences.computeIfAbsent(playerId, ignored -> new WeakHashMap<>())
            .computeIfAbsent(source, ignored -> new Cadence());
    }

    private int reserve(UUID playerId, int requested) {
        long tick = System.currentTimeMillis() / TICK_MILLIS;
        return admit(globalBudget, viewerBudgets.computeIfAbsent(playerId, ignored -> new Budget()),
            tick, requested, plugin.cfg().particles().samplesPerTick(),
            plugin.cfg().particles().samplesPerViewerPerTick());
    }

    /** The global pool may only lose what the viewer pool actually admits for emission. */
    static int admit(Budget global, Budget viewer, long tick, int requested, int globalLimit,
                     int viewerLimit) {
        int reserved = global.reserve(tick, requested, globalLimit);
        if (reserved == 0) {
            return 0;
        }
        int admitted = viewer.reserve(tick, reserved, viewerLimit);
        global.release(tick, reserved - admitted);
        return admitted;
    }

    private ResolvedParticle resolve(ParticleLayer.ParticleSpec spec) {
        NamespacedKey key = NamespacedKey.fromString(spec.key());
        Particle particle = RegistryUtil.find(Particle.class, key);
        if (particle == null) {
            throw new IllegalArgumentException("unknown particle key: " + spec.key());
        }
        if (spec.key().equals("minecraft:dust")) {
            String color = spec.color();
            int rgb = Integer.parseInt(color.substring(1), 16);
            return new ResolvedParticle(particle,
                new Particle.DustOptions(Color.fromRGB(rgb), spec.size().floatValue()));
        }
        if (particle.getDataType() != Void.class) {
            throw new IllegalArgumentException("particle " + spec.key() + " requires unsupported data type "
                + particle.getDataType().getSimpleName());
        }
        return new ResolvedParticle(particle, null);
    }

    private static List<Vector> select(List<Vector> samples, ParticleLayer.Emission emission, long tick) {
        if (samples.isEmpty() || emission.pattern().equals("steady")) {
            return samples;
        }
        int size = samples.size();
        int phase = (int) Math.floorMod(tick + emission.seed(), emission.periodTicks());
        return switch (emission.pattern()) {
            case "chase", "scan" -> List.of(samples.get((int) ((long) phase * size / emission.periodTicks()) % size));
            case "corners" -> corners(samples);
            case "pulse" -> phase * 2 < emission.periodTicks() ? samples : List.of();
            case "twinkle" -> twinkle(samples, emission.seed(), tick);
            default -> samples;
        };
    }

    private static List<Vector> corners(List<Vector> samples) {
        if (samples.size() <= 4) {
            return samples;
        }
        int last = samples.size() - 1;
        return List.of(samples.get(0), samples.get(last / 3), samples.get(last * 2 / 3), samples.get(last));
    }

    private static List<Vector> twinkle(List<Vector> samples, long seed, long tick) {
        int count = Math.max(1, samples.size() / 8);
        ArrayList<Vector> selected = new ArrayList<>(count);
        long state = seed ^ tick * 0x9E3779B97F4A7C15L;
        for (int index = 0; index < count; index++) {
            state ^= state >>> 12;
            state ^= state << 25;
            state ^= state >>> 27;
            selected.add(samples.get((int) Math.floorMod(state, samples.size())));
        }
        return List.copyOf(selected);
    }

    private static double square(double value) {
        return value * value;
    }

    private static final class Cadence {
        private final LinkedHashMap<String, Long> emittedTicks = new LinkedHashMap<>(8, 0.75F, true);

        private boolean isDue(ParticleLayer layer, long tick) {
            Long previous = emittedTicks.get(layer.id());
            return previous == null || tick < previous || tick - previous >= layer.emission().intervalTicks();
        }

        private void emitted(ParticleLayer layer, long tick) {
            emittedTicks.put(layer.id(), tick);
            if (emittedTicks.size() > ParticleLayer.MAX_LAYERS) {
                emittedTicks.pollFirstEntry();
            }
        }
    }
}
