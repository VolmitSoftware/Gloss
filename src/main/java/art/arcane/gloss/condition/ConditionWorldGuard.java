package art.arcane.gloss.condition;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Collection;

final class ConditionWorldGuard {
    private static final long RETRY_INTERVAL_MS = 5000L;

    private static volatile Adapter adapter;
    private static volatile long retryAfterMs;

    private ConditionWorldGuard() {
    }

    static boolean contains(Location location, String regionId) {
        Adapter active = adapter();
        return active != null && active.contains(location, regionId);
    }

    private static Adapter adapter() {
        Adapter active = adapter;
        if (active != null) {
            return active;
        }
        long now = System.currentTimeMillis();
        if (now < retryAfterMs) {
            return null;
        }
        synchronized (ConditionWorldGuard.class) {
            if (adapter != null) {
                return adapter;
            }
            retryAfterMs = now + RETRY_INTERVAL_MS;
            adapter = load();
            return adapter;
        }
    }

    private static Adapter load() {
        Plugin worldGuard = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (worldGuard == null || !worldGuard.isEnabled()) {
            return null;
        }
        try {
            ClassLoader classLoader = worldGuard.getClass().getClassLoader();
            Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard", true, classLoader);
            Object instance = worldGuardClass.getMethod("getInstance").invoke(null);
            Object platform = worldGuardClass.getMethod("getPlatform").invoke(instance);
            Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            Object query = container.getClass().getMethod("createQuery").invoke(container);
            Class<?> bukkitAdapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, classLoader);
            Class<?> worldEditLocationClass = Class.forName("com.sk89q.worldedit.util.Location", true, classLoader);
            Method adapt = bukkitAdapterClass.getMethod("adapt", Location.class);
            Method applicable = query.getClass().getMethod("getApplicableRegions", worldEditLocationClass);
            return new Adapter(query, adapt, applicable);
        } catch (ReflectiveOperationException | LinkageError failure) {
            return null;
        }
    }

    private record Adapter(Object query, Method adapt, Method applicable) {
        private boolean contains(Location location, String regionId) {
            try {
                Object worldEditLocation = adapt.invoke(null, location);
                Object applicableRegions = applicable.invoke(query, worldEditLocation);
                Method getRegions = applicableRegions.getClass().getMethod("getRegions");
                Collection<?> regions = (Collection<?>) getRegions.invoke(applicableRegions);
                for (Object region : regions) {
                    String id = (String) region.getClass().getMethod("getId").invoke(region);
                    if (id.equalsIgnoreCase(regionId)) {
                        return true;
                    }
                }
                return false;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return false;
            }
        }
    }
}
