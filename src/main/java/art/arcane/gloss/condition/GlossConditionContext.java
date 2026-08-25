package art.arcane.gloss.condition;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Map;

public record GlossConditionContext(Player viewer, Entity subject, Entity source, Location location,
                                    Map<String, Object> values) {
    public GlossConditionContext {
        location = location == null ? null : location.clone();
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public static GlossConditionContext viewer(Player player) {
        return new GlossConditionContext(player, player, null, player.getLocation(), Map.of());
    }

    public static GlossConditionContext subject(Entity subject) {
        Location location = subject == null ? null : subject.getLocation();
        return new GlossConditionContext(null, subject, null, location, Map.of());
    }

    public static GlossConditionContext subject(Player viewer, Entity subject, Entity source,
                                                Map<String, Object> values) {
        Entity locationOwner = subject == null ? viewer : subject;
        Location location = locationOwner == null ? null : locationOwner.getLocation();
        return new GlossConditionContext(viewer, subject, source, location, values);
    }
}
