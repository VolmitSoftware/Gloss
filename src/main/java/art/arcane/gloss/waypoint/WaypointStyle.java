package art.arcane.gloss.waypoint;

import java.util.Locale;

/** The two locator-bar icon styles the client ships. */
public enum WaypointStyle {
    DEFAULT,
    BOWTIE;

    public static WaypointStyle parse(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "default" -> DEFAULT;
            case "bowtie" -> BOWTIE;
            default -> throw new IllegalArgumentException("waypoint style must be default or bowtie: " + value);
        };
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
