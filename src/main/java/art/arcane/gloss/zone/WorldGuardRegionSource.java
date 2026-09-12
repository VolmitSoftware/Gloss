package art.arcane.gloss.zone;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Reads a WorldGuard region's geometry through reflection, exactly like the region condition does,
 * so Gloss never compiles against WorldGuard. A region that cannot be read resolves to nothing and
 * is retried on the next pass rather than logged every tick.
 */
public final class WorldGuardRegionSource {
    private static final long RETRY_INTERVAL_MS = 5000L;

    private final ConcurrentMap<String, ZoneShape> resolved = new ConcurrentHashMap<>();
    private volatile Object container;
    private volatile Method adaptWorld;
    private volatile long retryAfterMs;

    /** @return the region's shape, or null when WorldGuard, the world or the region is absent */
    public ZoneShape shape(World world, String regionId) {
        String key = world.getName() + "/" + regionId;
        ZoneShape cached = resolved.get(key);
        if (cached != null) {
            return cached;
        }
        Object activeContainer = container();
        if (activeContainer == null) {
            return null;
        }
        Object adapted = adaptWorld(world);
        if (adapted == null) {
            return null;
        }
        ZoneShape shape = read(activeContainer, adapted, world.getName(), regionId);
        if (shape != null) {
            resolved.put(key, shape);
        }
        return shape;
    }

    public void invalidate() {
        resolved.clear();
        container = null;
        adaptWorld = null;
        retryAfterMs = 0L;
    }

    /**
     * The reflective core: {@code container.get(world).getRegion(id)} then the region's corner and
     * point accessors. Kept package-visible and free of WorldGuard types so fakes can drive it.
     */
    static ZoneShape read(Object container, Object world, String worldName, String regionId) {
        try {
            Object manager = invoke(container, "get", world);
            if (manager == null) {
                return null;
            }
            Object region = invoke(manager, "getRegion", regionId);
            if (region == null) {
                return null;
            }
            double[] min = point3(invoke(region, "getMinimumPoint"));
            double[] max = point3(invoke(region, "getMaximumPoint"));
            if (min == null || max == null) {
                return null;
            }
            max = new double[]{max[0] + 1.0D, max[1] + 1.0D, max[2] + 1.0D};
            List<double[]> points = points2(invoke(region, "getPoints"));
            if (isCuboid(points, min, max)) {
                return ZoneShape.cuboid(worldName, min, max);
            }
            return ZoneShape.polygon(worldName, points, min[1], max[1]);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private Object container() {
        Object active = container;
        if (active != null) {
            return active;
        }
        long now = System.currentTimeMillis();
        if (now < retryAfterMs) {
            return null;
        }
        synchronized (this) {
            if (container != null) {
                return container;
            }
            retryAfterMs = now + RETRY_INTERVAL_MS;
            container = load();
            return container;
        }
    }

    private Object load() {
        Plugin worldGuard = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (worldGuard == null || !worldGuard.isEnabled()) {
            return null;
        }
        try {
            ClassLoader loader = worldGuard.getClass().getClassLoader();
            Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard", true, loader);
            Object instance = worldGuardClass.getMethod("getInstance").invoke(null);
            Object platform = worldGuardClass.getMethod("getPlatform").invoke(instance);
            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, loader);
            adaptWorld = adapter.getMethod("adapt", World.class);
            return platform.getClass().getMethod("getRegionContainer").invoke(platform);
        } catch (ReflectiveOperationException | LinkageError failure) {
            return null;
        }
    }

    private Object adaptWorld(World world) {
        Method adapt = adaptWorld;
        if (adapt == null) {
            return null;
        }
        try {
            return adapt.invoke(null, world);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    private static boolean isCuboid(List<double[]> points, double[] min, double[] max) {
        if (points.size() != 4) {
            return false;
        }
        for (double[] point : points) {
            boolean onX = near(point[0], min[0]) || near(point[0], max[0]);
            boolean onZ = near(point[1], min[2]) || near(point[1], max[2]);
            if (!onX || !onZ) {
                return false;
            }
        }
        return true;
    }

    private static boolean near(double value, double target) {
        return Math.abs(value - target) <= 1.0D;
    }

    private static double[] point3(Object vector) throws ReflectiveOperationException {
        if (vector == null) {
            return null;
        }
        return new double[]{number(vector, "getX", "x"), number(vector, "getY", "y"),
            number(vector, "getZ", "z")};
    }

    private static List<double[]> points2(Object list) throws ReflectiveOperationException {
        if (!(list instanceof List<?> values)) {
            return List.of();
        }
        List<double[]> points = new ArrayList<>(values.size());
        for (Object value : values) {
            points.add(new double[]{number(value, "getX", "x"), number(value, "getZ", "z")});
        }
        return List.copyOf(points);
    }

    private static double number(Object owner, String primary, String fallback)
        throws ReflectiveOperationException {
        Object value;
        try {
            value = owner.getClass().getMethod(primary).invoke(owner);
        } catch (NoSuchMethodException absent) {
            value = owner.getClass().getMethod(fallback).invoke(owner);
        }
        return ((Number) value).doubleValue();
    }

    private static Object invoke(Object owner, String name, Object... arguments)
        throws ReflectiveOperationException {
        for (Method method : owner.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) {
                continue;
            }
            return method.invoke(owner, arguments);
        }
        throw new NoSuchMethodException(owner.getClass().getName() + "#" + name);
    }
}
