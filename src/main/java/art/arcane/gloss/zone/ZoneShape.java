package art.arcane.gloss.zone;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The volume a zone occupies. A {@code region} shape carries no geometry of its own: the plugin
 * that owns the region is asked for it, and the resolved shape replaces this one at render time.
 */
public record ZoneShape(String type, String world, double[] min, double[] max, double[] center,
                        Double radius, Double minY, Double maxY, List<double[]> points,
                        String plugin, String id) {
    public static final Set<String> TYPES = Set.of("cuboid", "cylinder", "polygon", "region");

    public ZoneShape {
        type = normalize(type);
        world = world == null || world.isBlank() ? null : world.trim();
        min = copy(min, 3, "shape min");
        max = copy(max, 3, "shape max");
        center = copy(center, 2, "shape center");
        points = copyPoints(points);
        plugin = plugin == null || plugin.isBlank() ? null : plugin.trim().toLowerCase(Locale.ROOT);
        id = id == null || id.isBlank() ? null : id.trim();
        switch (type) {
            case "cuboid" -> requireCuboid(min, max);
            case "cylinder" -> requireCylinder(center, radius, minY, maxY);
            case "polygon" -> requirePolygon(points, minY, maxY);
            default -> requireRegion(plugin, id);
        }
    }

    public static ZoneShape cuboid(String world, double[] min, double[] max) {
        return new ZoneShape("cuboid", world, min, max, null, null, null, null, null, null, null);
    }

    public static ZoneShape polygon(String world, List<double[]> points, double minY, double maxY) {
        return new ZoneShape("polygon", world, null, null, null, null, minY, maxY, points, null, null);
    }

    public boolean isRegion() {
        return type.equals("region");
    }

    @Override
    public double[] min() {
        return min == null ? null : min.clone();
    }

    @Override
    public double[] max() {
        return max == null ? null : max.clone();
    }

    @Override
    public double[] center() {
        return center == null ? null : center.clone();
    }

    @Override
    public List<double[]> points() {
        if (points == null) {
            return List.of();
        }
        List<double[]> copied = new ArrayList<>(points.size());
        for (double[] point : points) {
            copied.add(point.clone());
        }
        return List.copyOf(copied);
    }

    private static String normalize(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!TYPES.contains(normalized)) {
            throw new IllegalArgumentException("zone shape type must be one of " + String.join(", ", TYPES));
        }
        return normalized;
    }

    private static void requireCuboid(double[] min, double[] max) {
        if (min == null || max == null) {
            throw new IllegalArgumentException("a cuboid zone requires min and max");
        }
        for (int axis = 0; axis < 3; axis++) {
            if (max[axis] < min[axis]) {
                throw new IllegalArgumentException("a cuboid zone requires max >= min on every axis");
            }
        }
    }

    private static void requireCylinder(double[] center, Double radius, Double minY, Double maxY) {
        if (center == null || radius == null || radius <= 0 || minY == null || maxY == null) {
            throw new IllegalArgumentException("a cylinder zone requires center, a positive radius, minY and maxY");
        }
        if (maxY < minY) {
            throw new IllegalArgumentException("a cylinder zone requires maxY >= minY");
        }
    }

    private static void requirePolygon(List<double[]> points, Double minY, Double maxY) {
        if (points == null || points.size() < 3 || minY == null || maxY == null) {
            throw new IllegalArgumentException("a polygon zone requires at least three points, minY and maxY");
        }
        if (maxY < minY) {
            throw new IllegalArgumentException("a polygon zone requires maxY >= minY");
        }
    }

    private static void requireRegion(String plugin, String id) {
        if (plugin == null || id == null) {
            throw new IllegalArgumentException("a region zone requires plugin and id");
        }
    }

    private static double[] copy(double[] value, int length, String noun) {
        if (value == null) {
            return null;
        }
        if (value.length != length) {
            throw new IllegalArgumentException(noun + " must have " + length + " components");
        }
        return value.clone();
    }

    private static List<double[]> copyPoints(List<double[]> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        List<double[]> copied = new ArrayList<>(points.size());
        for (double[] point : points) {
            Objects.requireNonNull(point, "zone shape points must not contain null entries");
            if (point.length != 2) {
                throw new IllegalArgumentException("zone shape points are [x, z] pairs");
            }
            copied.add(point.clone());
        }
        return List.copyOf(copied);
    }
}
