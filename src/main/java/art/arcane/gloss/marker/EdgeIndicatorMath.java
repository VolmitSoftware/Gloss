package art.arcane.gloss.marker;

import org.bukkit.util.Vector;

/**
 * Where an off-screen marker's arrow belongs. The client's field of view is not readable, so the
 * frustum is the vanilla default: 70 degrees vertical at 16:9. Screen coordinates run -1..1 with
 * +x to the viewer's right and +y up; {@link #offset} turns them back into a world offset from the
 * eye on a plane {@link #PLANE_DISTANCE} blocks ahead, which is where the indicator hologram sits.
 */
public final class EdgeIndicatorMath {
    public static final double FIELD_OF_VIEW_DEGREES = 70.0D;
    public static final double ASPECT_RATIO = 16.0D / 9.0D;
    public static final double PLANE_DISTANCE = 2.5D;

    private static final double VERTICAL_TANGENT = Math.tan(Math.toRadians(FIELD_OF_VIEW_DEGREES) / 2.0D);
    private static final double HORIZONTAL_TANGENT = VERTICAL_TANGENT * ASPECT_RATIO;
    private static final double DEGENERATE = 1.0E-9D;

    public enum Bearing {
        UP,
        DOWN,
        LEFT,
        RIGHT
    }

    public record Indicator(boolean inside, double screenX, double screenY, Bearing bearing) {
    }

    private EdgeIndicatorMath() {
    }

    public static double horizontalTangent() {
        return HORIZONTAL_TANGENT;
    }

    public static Indicator resolve(double yawDegrees, double pitchDegrees, Vector toMarker, double margin) {
        Vector forward = forward(yawDegrees, pitchDegrees);
        Vector right = right(yawDegrees);
        Vector up = up(forward, right);
        double length = toMarker.length();
        if (length < DEGENERATE) {
            return new Indicator(true, 0.0D, 0.0D, Bearing.UP);
        }
        Vector direction = toMarker.clone().multiply(1.0D / length);
        double ahead = direction.dot(forward);
        double lateral = direction.dot(right);
        double vertical = direction.dot(up);
        if (ahead > DEGENERATE) {
            double screenX = lateral / ahead / HORIZONTAL_TANGENT;
            double screenY = vertical / ahead / VERTICAL_TANGENT;
            if (Math.abs(screenX) <= 1.0D && Math.abs(screenY) <= 1.0D) {
                return new Indicator(true, screenX, screenY, bearing(screenX, screenY));
            }
            return clamped(screenX, screenY, margin);
        }
        if (Math.abs(lateral) < DEGENERATE && Math.abs(vertical) < DEGENERATE) {
            return clamped(0.0D, -1.0D, margin);
        }
        return clamped(lateral, vertical, margin);
    }

    /** The world offset from the eye to the indicator's point on the plane in front of the viewer. */
    public static Vector offset(double yawDegrees, double pitchDegrees, Indicator indicator) {
        Vector forward = forward(yawDegrees, pitchDegrees);
        Vector right = right(yawDegrees);
        Vector up = up(forward, right);
        return forward.multiply(PLANE_DISTANCE)
            .add(right.multiply(indicator.screenX() * HORIZONTAL_TANGENT * PLANE_DISTANCE))
            .add(up.multiply(indicator.screenY() * VERTICAL_TANGENT * PLANE_DISTANCE));
    }

    private static Indicator clamped(double x, double y, double margin) {
        double edge = Math.clamp(margin, 0.0D, 1.0D);
        double extent = Math.max(Math.abs(x), Math.abs(y));
        if (extent < DEGENERATE) {
            return new Indicator(false, 0.0D, 0.0D, Bearing.DOWN);
        }
        double factor = edge / extent;
        return new Indicator(false, x * factor, y * factor, bearing(x, y));
    }

    private static Bearing bearing(double x, double y) {
        if (Math.abs(x) >= Math.abs(y)) {
            return x >= 0.0D ? Bearing.RIGHT : Bearing.LEFT;
        }
        return y >= 0.0D ? Bearing.UP : Bearing.DOWN;
    }

    private static Vector forward(double yawDegrees, double pitchDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double horizontal = Math.cos(pitch);
        return new Vector(-Math.sin(yaw) * horizontal, -Math.sin(pitch), Math.cos(yaw) * horizontal);
    }

    private static Vector right(double yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new Vector(-Math.cos(yaw), 0.0D, -Math.sin(yaw));
    }

    private static Vector up(Vector forward, Vector right) {
        return right.clone().crossProduct(forward);
    }
}
