package art.arcane.gloss.waypoint;

import java.util.Objects;

/**
 * What one viewer should be tracking for one waypoint right now. {@code azimuthRadians} is null
 * while the exact position is sent and set once the waypoint is past its range, where the client
 * only learns a bearing.
 */
public record WaypointTarget(String id, int color, WaypointStyle style, double x, double y, double z,
                             Float azimuthRadians) {
    public WaypointTarget {
        id = Objects.requireNonNull(id, "waypoint id");
        color = color & 0xFFFFFF;
        style = style == null ? WaypointStyle.DEFAULT : style;
    }

    public boolean azimuth() {
        return azimuthRadians != null;
    }

    public double distanceTo(double otherX, double otherY, double otherZ) {
        double dx = x - otherX;
        double dy = y - otherY;
        double dz = z - otherZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
