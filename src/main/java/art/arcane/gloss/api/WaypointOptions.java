package art.arcane.gloss.api;

import art.arcane.gloss.waypoint.WaypointStyle;

public record WaypointOptions(String fallbackStyle) {
    public static final WaypointOptions DEFAULT = new WaypointOptions("default");

    public WaypointOptions {
        fallbackStyle = WaypointStyle.parse(fallbackStyle).serializedName();
    }
}
