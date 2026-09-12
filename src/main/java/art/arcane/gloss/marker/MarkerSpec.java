package art.arcane.gloss.marker;

import art.arcane.gloss.api.MarkerAnchor;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.config.icon.MenuIconData;

import java.util.Objects;

/**
 * One marker a viewer may see, whether it came from a {@code markers/} document, a registered
 * {@link art.arcane.gloss.api.MarkerProvider} or a player's own saved position.
 */
public record MarkerSpec(String id, MarkerAnchor anchor, String label, MenuIconData icon, int color,
                         String distanceScale, double hideWithin, double maxDistance,
                         Beam beam, Edge edge, Trail trail, ShowCondition audience,
                         long lifetimeTicks, boolean waypoint) {
    public static final double DEFAULT_MAX_DISTANCE = 256.0D;

    public MarkerSpec {
        id = Objects.requireNonNull(id, "marker id").trim();
        if (id.isEmpty() || id.length() > 64) {
            throw new IllegalArgumentException("marker id must be 1 to 64 characters");
        }
        anchor = Objects.requireNonNull(anchor, "marker anchor");
        label = label == null ? "" : label;
        color = color & 0xFFFFFF;
        distanceScale = distanceScale == null || distanceScale.isBlank() ? null : distanceScale.trim();
        hideWithin = clamp(hideWithin, 0.0D, 1024.0D, 0.0D);
        maxDistance = clamp(maxDistance, 1.0D, 4096.0D, DEFAULT_MAX_DISTANCE);
        beam = beam == null ? Beam.off() : beam;
        edge = edge == null ? Edge.off() : edge;
        trail = trail == null ? Trail.off() : trail;
        audience = audience == null ? ShowCondition.ALWAYS : audience;
        lifetimeTicks = Math.max(0L, lifetimeTicks);
    }

    public static MarkerSpec at(String id, String world, double x, double y, double z) {
        return new MarkerSpec(id, MarkerAnchor.position(world, x, y, z), "", null, MarkerColors.WHITE,
            null, 0.0D, DEFAULT_MAX_DISTANCE, null, null, null, ShowCondition.ALWAYS, 0L, false);
    }

    public MarkerSpec withLabel(String value) {
        return new MarkerSpec(id, anchor, value, icon, color, distanceScale, hideWithin, maxDistance,
            beam, edge, trail, audience, lifetimeTicks, waypoint);
    }

    public MarkerSpec asWaypoint() {
        return new MarkerSpec(id, anchor, label, icon, color, distanceScale, hideWithin, maxDistance,
            beam, edge, trail, audience, lifetimeTicks, true);
    }

    public record Beam(boolean enabled, double height, double width, String material) {
        private static final Beam OFF = new Beam(false, 48.0D, 0.25D, "minecraft:white_stained_glass");

        public Beam {
            height = clamp(height, 1.0D, 384.0D, 48.0D);
            width = clamp(width, 0.02D, 8.0D, 0.25D);
            material = material == null || material.isBlank() ? "minecraft:white_stained_glass" : material.trim();
        }

        public static Beam off() {
            return OFF;
        }
    }

    public record Edge(boolean enabled, double margin, String arrow) {
        private static final Edge OFF = new Edge(false, 0.8D, "&f>");

        public Edge {
            margin = clamp(margin, 0.0D, 1.0D, 0.8D);
            arrow = arrow == null ? "" : arrow;
        }

        public static Edge off() {
            return OFF;
        }
    }

    public record Trail(boolean enabled, String particle, double spacing, int maxPoints) {
        private static final Trail OFF = new Trail(false, "minecraft:end_rod", 2.0D, 48);

        public Trail {
            particle = particle == null || particle.isBlank() ? "minecraft:end_rod" : particle.trim();
            spacing = clamp(spacing, 0.25D, 16.0D, 2.0D);
            maxPoints = maxPoints <= 0 ? 48 : Math.clamp(maxPoints, 1, 256);
        }

        public static Trail off() {
            return OFF;
        }
    }

    private static double clamp(double value, double minimum, double maximum, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, minimum, maximum) : fallback;
    }
}
