package art.arcane.gloss.particle;

import art.arcane.volmlib.util.bukkit.registry.RegistryUtil;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a particle key and colour once per key instead of per emitted point, and spawns it for
 * one viewer only. Zones, beams and marker trails all draw through here.
 */
public final class ViewerParticles {
    public record Resolved(Particle particle, Object data) {
    }

    /** A key this server does not know, remembered so the lookup is not redone on every pass. */
    private static final Resolved UNKNOWN = new Resolved(null, null);

    private static final Map<String, Resolved> CACHE = new ConcurrentHashMap<>();

    private ViewerParticles() {
    }

    /** @return the resolved particle, or null when the key names no particle on this server */
    public static Resolved resolve(String key, int rgb) {
        Resolved resolved = CACHE.computeIfAbsent(key + "#" + rgb, ignored -> {
            NamespacedKey namespaced = NamespacedKey.fromString(key);
            Particle particle = namespaced == null ? null : RegistryUtil.find(Particle.class, namespaced);
            if (particle == null) {
                return UNKNOWN;
            }
            if (particle.getDataType() == Particle.DustOptions.class) {
                return new Resolved(particle, new Particle.DustOptions(Color.fromRGB(rgb), 1.0F));
            }
            return particle.getDataType() == Void.class ? new Resolved(particle, null) : UNKNOWN;
        });
        return resolved == UNKNOWN ? null : resolved;
    }

    public static void spawn(Player viewer, Resolved resolved, World world, Vector point) {
        Location at = new Location(world, point.getX(), point.getY(), point.getZ());
        if (resolved.data() == null) {
            viewer.spawnParticle(resolved.particle(), at, 1);
        } else {
            viewer.spawnParticle(resolved.particle(), at, 1, resolved.data());
        }
    }

    public static void clear() {
        CACHE.clear();
    }
}
