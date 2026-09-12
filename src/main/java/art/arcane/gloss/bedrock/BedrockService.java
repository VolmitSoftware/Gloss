package art.arcane.gloss.bedrock;

import art.arcane.gloss.Gloss;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Detects Bedrock (Geyser) viewers. Display entities, custom glyphs and packet menus do not render
 * on Bedrock, so every surface asks here before spending packets on a viewer. Detection is a
 * reflective probe of Floodgate first and the Geyser API second, retried every five seconds while
 * neither is loaded; the UUID heuristic (Floodgate assigns UUIDs whose upper 64 bits are zero) is
 * only used when an operator selects it explicitly.
 */
public final class BedrockService {
    private static final long RETRY_INTERVAL_MS = 5000L;
    private static final String FLOODGATE_PLUGIN = "floodgate";
    private static final String FLOODGATE_API = "org.geysermc.floodgate.api.FloodgateApi";
    private static final String GEYSER_PLUGIN = "Geyser-Spigot";
    private static final String GEYSER_API = "org.geysermc.geyser.api.GeyserApi";

    public enum Detection {
        AUTO,
        FLOODGATE,
        GEYSER,
        UUID,
        OFF;

        public static Detection parse(String value) {
            if (value == null) {
                return AUTO;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                return AUTO;
            }
        }
    }

    private volatile Detection detection;
    private volatile Probe probe;
    private volatile long retryAfterMs;

    public BedrockService(Detection detection) {
        this.detection = Objects.requireNonNull(detection, "detection");
    }

    /** Null-safe entry point for render paths that may run before {@link Gloss} has a service. */
    public static boolean isBedrockPlayer(UUID playerId) {
        Gloss plugin = Gloss.instance;
        BedrockService service = plugin == null ? null : plugin.bedrock();
        return service != null && service.isBedrock(playerId);
    }

    public Detection detection() {
        return detection;
    }

    public void detection(Detection next) {
        Objects.requireNonNull(next, "detection");
        if (next != detection) {
            detection = next;
            probe = null;
            retryAfterMs = 0L;
        }
    }

    public boolean isBedrock(Player player) {
        return player != null && isBedrock(player.getUniqueId());
    }

    public boolean isBedrock(UUID playerId) {
        if (playerId == null || detection == Detection.OFF) {
            return false;
        }
        Probe active = probe();
        return active != null && active.isBedrock(playerId);
    }

    /** The active detector name for diagnostics: floodgate, geyser, uuid, off, or none. */
    public String describe() {
        if (detection == Detection.OFF) {
            return "off";
        }
        Probe active = probe();
        return active == null ? "none" : active.name();
    }

    static boolean looksLikeFloodgateUuid(UUID id) {
        return id != null && id.getMostSignificantBits() == 0L;
    }

    private Probe probe() {
        Probe active = probe;
        if (active != null) {
            return active;
        }
        long now = System.currentTimeMillis();
        if (now < retryAfterMs) {
            return null;
        }
        synchronized (this) {
            if (probe != null) {
                return probe;
            }
            retryAfterMs = now + RETRY_INTERVAL_MS;
            probe = load(detection);
            return probe;
        }
    }

    private static Probe load(Detection detection) {
        return switch (detection) {
            case OFF -> null;
            case UUID -> new UuidProbe();
            case FLOODGATE -> floodgate();
            case GEYSER -> geyser();
            case AUTO -> {
                Probe found = floodgate();
                yield found != null ? found : geyser();
            }
        };
    }

    private static Probe floodgate() {
        Plugin plugin = enabledPlugin(FLOODGATE_PLUGIN);
        if (plugin == null) {
            return null;
        }
        try {
            Class<?> api = Class.forName(FLOODGATE_API, true, plugin.getClass().getClassLoader());
            Object instance = api.getMethod("getInstance").invoke(null);
            Method check = instance.getClass().getMethod("isFloodgatePlayer", UUID.class);
            return new ReflectiveProbe("floodgate", instance, check);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return null;
        }
    }

    private static Probe geyser() {
        Plugin plugin = enabledPlugin(GEYSER_PLUGIN);
        if (plugin == null) {
            return null;
        }
        try {
            Class<?> api = Class.forName(GEYSER_API, true, plugin.getClass().getClassLoader());
            Object instance = api.getMethod("api").invoke(null);
            Method check = instance.getClass().getMethod("isBedrockPlayer", UUID.class);
            return new ReflectiveProbe("geyser", instance, check);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return null;
        }
    }

    /** Null when the server is not running (headless tests) or the plugin is absent or disabled. */
    private static Plugin enabledPlugin(String name) {
        if (Bukkit.getServer() == null) {
            return null;
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin == null || !plugin.isEnabled() ? null : plugin;
    }

    private interface Probe {
        String name();

        boolean isBedrock(UUID playerId);
    }

    private static final class UuidProbe implements Probe {
        @Override
        public String name() {
            return "uuid";
        }

        @Override
        public boolean isBedrock(UUID playerId) {
            return looksLikeFloodgateUuid(playerId);
        }
    }

    private record ReflectiveProbe(String name, Object instance, Method check) implements Probe {
        @Override
        public boolean isBedrock(UUID playerId) {
            try {
                return Boolean.TRUE.equals(check.invoke(instance, playerId));
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return false;
            }
        }
    }
}
