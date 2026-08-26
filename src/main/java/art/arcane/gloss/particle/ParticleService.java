package art.arcane.gloss.particle;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.ParticleLayer;
import art.arcane.volmlib.util.bukkit.registry.RegistryUtil;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ParticleService {
    private static final long TICK_MILLIS = 50L;
    private static final int MAX_SAMPLE_CACHE_ENTRIES = 2048;

    private record ResolvedParticle(Particle particle, Object data) {
    }

    private record SampleKey(ParticleLayer.Geometry geometry, List<ParticleRect> targets, int limit) {
    }

    private static final class Budget {
        private final AtomicLong tick = new AtomicLong(Long.MIN_VALUE);
        private final AtomicInteger used = new AtomicInteger();

        private int reserve(long currentTick, int requested, int limit) {
            long observed = tick.get();
            if (observed != currentTick && tick.compareAndSet(observed, currentTick)) {
                used.set(0);
            }
            while (true) {
                int current = used.get();
                int admitted = Math.min(requested, Math.max(0, limit - current));
                if (admitted == 0) {
                    return 0;
                }
                if (used.compareAndSet(current, current + admitted)) {
                    return admitted;
                }
            }
        }
    }

    private final Gloss plugin;
    private final Map<ParticleLayer.ParticleSpec, ResolvedParticle> particles;
    private final Map<SampleKey, List<Vector>> samples;
    private final Map<UUID, Budget> viewerBudgets;
    private final Budget globalBudget;

    public ParticleService(Gloss plugin) {
        this.plugin = plugin;
        this.particles = new ConcurrentHashMap<>();
        this.samples = new ConcurrentHashMap<>();
        this.viewerBudgets = new ConcurrentHashMap<>();
        this.globalBudget = new Budget();
    }

    public void emit(Player viewer, ParticleFrame frame, ParticleLayer layer,
                     List<ParticleRect> targets, long tick) {
        if (!plugin.cfg().particles().enabled() || !viewer.isOnline()
            || tick % layer.emission().intervalTicks() != 0L) {
            return;
        }
        Location origin = frame.origin();
        Location viewerLocation = viewer.getLocation();
        if (origin.getWorld() != viewerLocation.getWorld()
            || origin.distanceSquared(viewerLocation) > square(plugin.cfg().particles().viewRange())) {
            return;
        }
        int cachedLimit = plugin.cfg().particles().maxCachedSamplesPerLayer();
        List<ParticleRect> stableTargets = targets == null ? List.of() : List.copyOf(targets);
        SampleKey sampleKey = new SampleKey(layer.geometry(), stableTargets, cachedLimit);
        List<Vector> sampled = samples.get(sampleKey);
        if (sampled == null) {
            sampled = ParticleGeometrySampler.sample(layer.geometry(), stableTargets, cachedLimit);
            if (samples.size() < MAX_SAMPLE_CACHE_ENTRIES) {
                List<Vector> raced = samples.putIfAbsent(sampleKey, sampled);
                if (raced != null) {
                    sampled = raced;
                }
            }
        }
        List<Vector> selected = select(sampled, layer.emission(), tick);
        int admitted = reserve(viewer.getUniqueId(), selected.size());
        if (admitted == 0) {
            return;
        }
        ResolvedParticle resolved = particles.computeIfAbsent(layer.particle(), this::resolve);
        for (int index = 0; index < admitted; index++) {
            Location point = frame.world(selected.get(index), layer.placement());
            if (resolved.data() == null) {
                viewer.spawnParticle(resolved.particle(), point, 1);
            } else {
                viewer.spawnParticle(resolved.particle(), point, 1, resolved.data());
            }
        }
    }

    public void prune(UUID playerId) {
        viewerBudgets.remove(playerId);
    }

    public void clear() {
        particles.clear();
        samples.clear();
        viewerBudgets.clear();
    }

    private int reserve(UUID playerId, int requested) {
        long tick = System.currentTimeMillis() / TICK_MILLIS;
        int global = globalBudget.reserve(tick, requested, plugin.cfg().particles().samplesPerTick());
        if (global == 0) {
            return 0;
        }
        Budget viewer = viewerBudgets.computeIfAbsent(playerId, ignored -> new Budget());
        return viewer.reserve(tick, global, plugin.cfg().particles().samplesPerViewerPerTick());
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
}
