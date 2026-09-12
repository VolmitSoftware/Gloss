package art.arcane.gloss.beam;

import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3f;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * The transform that stretches one block display between two world points. A display's unrotated
 * box runs along its local {@code +Z}, so a beam is "point local +Z at the target" plus a scale
 * whose z component is the distance; the handedness is pinned here and tested against fixed
 * vectors so a later refactor cannot quietly mirror every beam.
 */
public final class BeamMath {
    /** Blocks a trail's viewer end may move before the trail is walked again. */
    public static final double TRAIL_RESAMPLE_DISTANCE = 2.0D;

    private static final double DEGENERATE = 1.0E-9D;

    public record Transform(Vector midpoint, Quaternion4f rotation, Vector3f scale) {
        public Transform {
            midpoint = midpoint.clone();
        }

        @Override
        public Vector midpoint() {
            return midpoint.clone();
        }
    }

    private BeamMath() {
    }

    public static Transform beamBetween(Vector from, Vector to, double width) {
        Vector delta = to.clone().subtract(from);
        double length = delta.length();
        Vector midpoint = from.clone().add(to).multiply(0.5D);
        Vector3f scale = new Vector3f((float) width, (float) width, (float) length);
        if (length < DEGENERATE) {
            return new Transform(midpoint, new Quaternion4f(0.0F, 0.0F, 0.0F, 1.0F), scale);
        }
        Vector direction = delta.multiply(1.0D / length);
        double yaw = Math.atan2(direction.getX(), direction.getZ());
        double pitch = -Math.asin(Math.clamp(direction.getY(), -1.0D, 1.0D));
        return new Transform(midpoint, multiply(aboutY(yaw), aboutX(pitch)), scale);
    }

    public static List<Vector> trailPoints(Vector from, Vector to, double spacing, int maxPoints) {
        int limit = Math.max(1, maxPoints);
        Vector delta = to.clone().subtract(from);
        double length = delta.length();
        if (length < DEGENERATE) {
            return List.of(from.clone());
        }
        int steps = Math.max(1, (int) Math.ceil(length / Math.max(0.05D, spacing)));
        List<Vector> points = new ArrayList<>(Math.min(limit, steps + 1));
        for (int index = 0; index <= steps && points.size() < limit; index++) {
            points.add(from.clone().add(delta.clone().multiply((double) index / steps)));
        }
        return List.copyOf(points);
    }

    public static boolean shouldResample(Vector sampledAt, Vector now) {
        return sampledAt == null || sampledAt.distance(now) > TRAIL_RESAMPLE_DISTANCE;
    }

    public static Vector rotate(Quaternion4f rotation, Vector point) {
        double x = rotation.getX();
        double y = rotation.getY();
        double z = rotation.getZ();
        double w = rotation.getW();
        double px = point.getX();
        double py = point.getY();
        double pz = point.getZ();
        double ix = w * px + y * pz - z * py;
        double iy = w * py + z * px - x * pz;
        double iz = w * pz + x * py - y * px;
        double iw = -x * px - y * py - z * pz;
        return new Vector(
            ix * w + iw * -x + iy * -z - iz * -y,
            iy * w + iw * -y + iz * -x - ix * -z,
            iz * w + iw * -z + ix * -y - iy * -x);
    }

    private static Quaternion4f aboutY(double radians) {
        return new Quaternion4f(0.0F, (float) Math.sin(radians / 2.0D), 0.0F, (float) Math.cos(radians / 2.0D));
    }

    private static Quaternion4f aboutX(double radians) {
        return new Quaternion4f((float) Math.sin(radians / 2.0D), 0.0F, 0.0F, (float) Math.cos(radians / 2.0D));
    }

    private static Quaternion4f multiply(Quaternion4f left, Quaternion4f right) {
        float lx = left.getX();
        float ly = left.getY();
        float lz = left.getZ();
        float lw = left.getW();
        float rx = right.getX();
        float ry = right.getY();
        float rz = right.getZ();
        float rw = right.getW();
        return new Quaternion4f(
            lw * rx + lx * rw + ly * rz - lz * ry,
            lw * ry - lx * rz + ly * rw + lz * rx,
            lw * rz + lx * ry - ly * rx + lz * rw,
            lw * rw - lx * rx - ly * ry - lz * rz);
    }
}
