package art.arcane.gloss.zone;

import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Picks the points an ambience layer emits: inside the zone and inside the viewer's radius, so a
 * zone larger than the view distance still only spends particles where they can be seen. Rejection
 * sampling is bounded; an awkward shape emits fewer particles rather than spinning.
 */
public final class ZoneAmbience {
    private static final int ATTEMPTS_PER_POINT = 8;

    private ZoneAmbience() {
    }

    public static List<Vector> points(ZoneShape shape, Vector viewer, double radius, int count,
                                      RandomGenerator random) {
        if (count <= 0 || radius <= 0.0D) {
            return List.of();
        }
        List<Vector> points = new ArrayList<>(count);
        int attempts = count * ATTEMPTS_PER_POINT;
        for (int attempt = 0; attempt < attempts && points.size() < count; attempt++) {
            Vector candidate = sample(viewer, radius, random);
            if (ZoneGeometry.contains(shape, candidate)) {
                points.add(candidate);
            }
        }
        return List.copyOf(points);
    }

    private static Vector sample(Vector viewer, double radius, RandomGenerator random) {
        double distance = radius * Math.cbrt(random.nextDouble());
        double theta = random.nextDouble() * 2.0D * Math.PI;
        double cosPhi = random.nextDouble() * 2.0D - 1.0D;
        double sinPhi = Math.sqrt(1.0D - cosPhi * cosPhi);
        return new Vector(
            viewer.getX() + distance * sinPhi * Math.cos(theta),
            viewer.getY() + distance * cosPhi,
            viewer.getZ() + distance * sinPhi * Math.sin(theta));
    }
}
