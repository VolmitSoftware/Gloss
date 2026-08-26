package art.arcane.gloss.api;

import org.bukkit.NamespacedKey;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record ParticleLayer(String id, Target target, Geometry geometry, Placement placement,
                            ParticleSpec particle, Emission emission, int priority) {
    public static final int MAX_LAYERS = 64;

    private static final Set<String> TARGET_SCOPES = Set.of(
        "projection", "component", "text", "line", "span", "label", "model", "local");
    private static final Set<String> GEOMETRY_TYPES = Set.of(
        "point", "line", "polyline", "outline", "filledPlane", "cuboid",
        "letterBounds", "glyphOutline", "glyphFill");
    private static final Set<String> PLACEMENT_LAYERS = Set.of("behind", "front", "center");
    private static final Set<String> EMISSION_PATTERNS = Set.of(
        "steady", "chase", "pulse", "twinkle", "scan", "corners");

    public ParticleLayer {
        id = requireId(id);
        target = Objects.requireNonNull(target, "particle layer requires a target");
        geometry = Objects.requireNonNull(geometry, "particle layer requires geometry");
        placement = placement == null ? Placement.behind() : placement;
        particle = Objects.requireNonNull(particle, "particle layer requires a particle");
        emission = emission == null ? Emission.defaults() : emission;
        priority = Math.max(-1000, Math.min(1000, priority));
    }

    public static List<ParticleLayer> copyLayers(List<ParticleLayer> layers, String owner) {
        if (layers == null || layers.isEmpty()) {
            return List.of();
        }
        if (layers.size() > MAX_LAYERS) {
            throw new IllegalArgumentException(owner + " may declare at most " + MAX_LAYERS + " particle layers");
        }
        List<ParticleLayer> copied = new ArrayList<>(layers.size());
        Set<String> ids = new HashSet<>(layers.size());
        for (ParticleLayer layer : layers) {
            ParticleLayer value = Objects.requireNonNull(layer, owner + " particle layers must not contain null entries");
            if (!ids.add(value.id())) {
                throw new IllegalArgumentException(owner + " contains duplicate particle layer id: " + value.id());
            }
            copied.add(value);
        }
        copied.sort(Comparator.comparingInt(ParticleLayer::priority).reversed());
        return List.copyOf(copied);
    }

    public record Target(String scope, String name, String component, Integer line) {
        public Target {
            scope = normalizeChoice(scope, "target scope", TARGET_SCOPES);
            name = normalizeOptionalId(name, "particle span name");
            component = normalizeOptionalId(component, "particle component id");
            if (line != null && line < 1) {
                throw new IllegalArgumentException("particle target line must be one-based and greater than zero");
            }
            if (scope.equals("span") && name == null) {
                throw new IllegalArgumentException("particle span target requires a name");
            }
            if (scope.equals("component") && component == null) {
                throw new IllegalArgumentException("particle component target requires a component id");
            }
            if (scope.equals("line") && line == null) {
                throw new IllegalArgumentException("particle line target requires a line number");
            }
        }
    }

    public record Geometry(String type, Vector from, Vector to, List<Vector> points,
                           Double width, Double height, Double depth, Double padding, Double spacing) {
        public Geometry {
            type = normalizeChoice(type, "geometry type", GEOMETRY_TYPES);
            from = cloneVector(from);
            to = cloneVector(to);
            points = copyVectors(points);
            width = finiteRange(width, 0.0D, 128.0D, "particle geometry width");
            height = finiteRange(height, 0.0D, 128.0D, "particle geometry height");
            depth = finiteRange(depth, 0.0D, 128.0D, "particle geometry depth");
            padding = finiteRange(padding == null ? 0.0D : padding, 0.0D, 16.0D,
                "particle geometry padding");
            spacing = finiteRange(spacing == null ? 0.15D : spacing, 0.02D, 16.0D,
                "particle geometry spacing");
            if (type.equals("line") && (from == null || to == null)) {
                throw new IllegalArgumentException("particle line geometry requires from and to vectors");
            }
            if (type.equals("polyline") && points.size() < 2) {
                throw new IllegalArgumentException("particle polyline geometry requires at least two points");
            }
        }

        @Override
        public Vector from() {
            return cloneVector(from);
        }

        @Override
        public Vector to() {
            return cloneVector(to);
        }

        @Override
        public List<Vector> points() {
            return copyVectors(points);
        }
    }

    public record Placement(String layer, double depth, Vector offset) {
        public Placement {
            layer = normalizeChoice(layer, "placement layer", PLACEMENT_LAYERS);
            if (!Double.isFinite(depth) || depth < 0.0D || depth > 16.0D) {
                throw new IllegalArgumentException("particle placement depth must be between 0 and 16");
            }
            offset = offset == null ? new Vector() : offset.clone();
        }

        public static Placement behind() {
            return new Placement("behind", 0.04D, new Vector());
        }

        @Override
        public Vector offset() {
            return offset.clone();
        }

        public double signedDepth() {
            return switch (layer) {
                case "behind" -> depth;
                case "front" -> -depth;
                default -> 0.0D;
            };
        }
    }

    public record ParticleSpec(String key, String color, Double size) {
        public ParticleSpec {
            key = normalizeKey(key);
            boolean dust = key.equals("minecraft:dust");
            if (dust) {
                color = normalizeColor(color == null ? "#ffffff" : color);
                size = finiteRange(size == null ? 1.0D : size, 0.01D, 4.0D, "dust particle size");
            } else {
                if (color != null || size != null) {
                    throw new IllegalArgumentException("particle color and size are only valid for minecraft:dust");
                }
            }
        }
    }

    public record Emission(Integer intervalTicks, String pattern, Integer periodTicks, Long seed) {
        public Emission {
            intervalTicks = intervalTicks == null ? 4 : Math.max(1, Math.min(200, intervalTicks));
            pattern = normalizeChoice(pattern == null ? "steady" : pattern,
                "emission pattern", EMISSION_PATTERNS);
            periodTicks = periodTicks == null ? 40 : Math.max(1, Math.min(72000, periodTicks));
            seed = seed == null ? 0L : seed;
        }

        public static Emission defaults() {
            return new Emission(4, "steady", 40, 0L);
        }
    }

    private static String requireId(String value) {
        String normalized = normalizeOptionalId(value, "particle layer id");
        if (normalized == null) {
            throw new IllegalArgumentException("particle layer id may not be blank");
        }
        return normalized;
    }

    private static String normalizeOptionalId(String value, String noun) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 64 || !normalized.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException(noun + " must match [a-z0-9][a-z0-9._-]* and be at most 64 characters");
        }
        return normalized;
    }

    private static String normalizeChoice(String value, String noun, Set<String> choices) {
        String normalized = value == null ? "" : value.trim();
        for (String choice : choices) {
            if (choice.equalsIgnoreCase(normalized)) {
                return choice;
            }
        }
        throw new IllegalArgumentException(noun + " must be one of " + String.join(", ", choices));
    }

    private static String normalizeKey(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        NamespacedKey key = NamespacedKey.fromString(normalized);
        if (key == null || !key.toString().equals(normalized)) {
            throw new IllegalArgumentException("particle key must be a canonical namespaced key");
        }
        return normalized;
    }

    private static String normalizeColor(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("#[0-9a-f]{6}")) {
            throw new IllegalArgumentException("dust particle color must be #RRGGBB");
        }
        return normalized;
    }

    private static Double finiteRange(Double value, double minimum, double maximum, String noun) {
        if (value == null) {
            return null;
        }
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(noun + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static Vector cloneVector(Vector vector) {
        return vector == null ? null : vector.clone();
    }

    private static List<Vector> copyVectors(List<Vector> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            return List.of();
        }
        List<Vector> copied = new ArrayList<>(vectors.size());
        for (Vector vector : vectors) {
            copied.add(Objects.requireNonNull(vector, "particle geometry points must not contain null entries").clone());
        }
        return List.copyOf(copied);
    }
}
