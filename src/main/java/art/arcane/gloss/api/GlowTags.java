package art.arcane.gloss.api;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.glow.GlowService;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Per-viewer entity outlines for other plugins. Tags stack by priority under their own purpose, so
 * two plugins can both want the same entity to glow without either clearing the other's colour.
 */
public final class GlowTags {
    private GlowTags() {
    }

    /**
     * @param color     a named text colour ({@code red}, {@code aqua}, ...)
     * @param priority  higher wins when several owners tag the same entity for the same viewer
     * @param ttlTicks  ticks the tag lives for, or {@code 0} to keep it until it is untagged
     */
    public static void tag(Player viewer, Entity target, String color, String purpose, int priority,
                           long ttlTicks) {
        GlowService service = service();
        if (service != null && service.enabled()) {
            service.tag(viewer, target, color, purpose, priority, ttlTicks);
        }
    }

    public static void untag(Player viewer, Entity target, String purpose) {
        GlowService service = service();
        if (service != null) {
            service.untag(viewer, target, purpose);
        }
    }

    private static GlowService service() {
        Gloss plugin = Gloss.instance;
        return plugin == null ? null : plugin.service(GlowService.class);
    }
}
