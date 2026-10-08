package art.arcane.gloss.waypoint;

import java.util.Locale;
import java.util.regex.Pattern;

public record WaypointStyleKey(String value) {
    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public WaypointStyleKey {
        value = value == null || value.isBlank() ? "default" : value.trim().toLowerCase(Locale.ROOT);
        if (!value.equals("default") && !value.equals("bowtie")
            && (!KEY.matcher(value).matches() || value.contains("..") || value.contains("//")
                || value.endsWith("/") || value.contains(":/"))) {
            throw new IllegalArgumentException("Waypoint style must be default, bowtie, or a namespaced resource key: " + value);
        }
        if (value.equals("minecraft:default") || value.equals("minecraft:bowtie")) {
            value = value.substring("minecraft:".length());
        }
    }

    public String select(String fallback, boolean packReady) {
        return builtIn() || packReady ? resourceKey()
            : "minecraft:" + WaypointStyle.parse(fallback).serializedName();
    }

    public boolean builtIn() {
        return value.equals("default") || value.equals("bowtie");
    }

    public String resourceKey() {
        return builtIn() ? "minecraft:" + value : value;
    }
}
