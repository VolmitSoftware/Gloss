package art.arcane.gloss.zone;

import com.github.retrooper.packetevents.util.Quaternion4f;
import org.bukkit.util.Vector;

/**
 * Rotations that point a block display's local {@code +Z} at a face's outward normal. A display's
 * unrotated quad faces south, so a wall panel is a yaw about Y followed by a pitch about X with the
 * handedness pinned here rather than rediscovered per caller.
 */
public final class FaceQuaternions {
    private FaceQuaternions() {
    }

    public static Quaternion4f of(Vector normal) {
        Vector unit = normal.clone().normalize();
        double yaw = Math.atan2(unit.getX(), unit.getZ());
        double pitch = -Math.asin(Math.clamp(unit.getY(), -1.0D, 1.0D));
        return multiply(aboutY(yaw), aboutX(pitch));
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
