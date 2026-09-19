package art.arcane.gloss.marker;

import java.util.Locale;
import java.util.regex.Pattern;

/** Parses the {@code #RRGGBB} color strings marker and waypoint documents carry. */
public final class MarkerColors {
    public static final int WHITE = 0xFFFFFF;

    private static final Pattern HEX = Pattern.compile("#[0-9a-f]{6}");

    private MarkerColors() {
    }

    public static int parse(String value, String noun) {
        if (value == null || value.isBlank()) {
            return WHITE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!HEX.matcher(normalized).matches()) {
            throw new IllegalArgumentException(noun + " must be #RRGGBB: " + value);
        }
        return Integer.parseInt(normalized.substring(1), 16);
    }
}
