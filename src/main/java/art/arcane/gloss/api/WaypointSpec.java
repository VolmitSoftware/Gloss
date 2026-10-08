package art.arcane.gloss.api;

import art.arcane.gloss.waypoint.WaypointStyleKey;
import java.util.Objects;

/**
 * One locator-bar entry other plugins hand to {@link Waypoints}. {@code style} is {@code default}
 * or {@code bowtie}, or a namespaced style from a loaded Gloss pack; {@code range} of zero means the exact position is always sent, and beyond a
 * positive range the client is given a direction only.
 */
public record WaypointSpec(String id, MarkerAnchor anchor, int color, String style, double range) {
    public WaypointSpec {
        id = Objects.requireNonNull(id, "waypoint id").trim();
        if (id.isEmpty() || id.length() > 64) {
            throw new IllegalArgumentException("waypoint id must be 1 to 64 characters");
        }
        anchor = Objects.requireNonNull(anchor, "waypoint anchor");
        color = color & 0xFFFFFF;
        style = new WaypointStyleKey(style).value();
        range = Double.isFinite(range) ? Math.clamp(range, 0.0D, 8192.0D) : 0.0D;
    }
}
