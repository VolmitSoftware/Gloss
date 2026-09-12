package art.arcane.gloss.marker;

import art.arcane.gloss.state.PlayerSections;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A player's own saved positions, stored in the {@code markers} section of
 * {@code state/players/&lt;uuid&gt;.json}. Names are matched case insensitively; the authored
 * casing is kept as the marker's label.
 */
public final class PersonalMarkers {
    public static final String SECTION = "markers";
    public static final int MAX_PER_PLAYER = 32;

    private static final String LABEL = "label";
    private static final String WORLD = "world";
    private static final String X = "x";
    private static final String Y = "y";
    private static final String Z = "z";

    private final PlayerSections sections;

    public PersonalMarkers(PlayerSections sections) {
        this.sections = Objects.requireNonNull(sections, "sections");
    }

    public boolean set(UUID player, String name, Location location) {
        Objects.requireNonNull(location, "location");
        return set(player, name, location.getWorld().getName(),
            location.getX(), location.getY(), location.getZ());
    }

    /** @return false when the player already holds {@link #MAX_PER_PLAYER} differently named markers */
    public boolean set(UUID player, String name, String world, double x, double y, double z) {
        String key = key(name);
        Map<String, Object> stored = new LinkedHashMap<>(sections.read(player, SECTION));
        if (!stored.containsKey(key) && stored.size() >= MAX_PER_PLAYER) {
            return false;
        }
        stored.put(key, new LinkedHashMap<>(Map.of(
            LABEL, name.trim(), WORLD, world, X, x, Y, y, Z, z)));
        sections.write(player, SECTION, stored);
        return true;
    }

    public boolean remove(UUID player, String name) {
        String key = key(name);
        Map<String, Object> stored = new LinkedHashMap<>(sections.read(player, SECTION));
        if (stored.remove(key) == null) {
            return false;
        }
        sections.write(player, SECTION, stored);
        return true;
    }

    public List<MarkerSpec> list(UUID player) {
        Map<String, Object> stored = sections.read(player, SECTION);
        List<MarkerSpec> markers = new ArrayList<>(stored.size());
        for (Map.Entry<String, Object> entry : stored.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> values)) {
                continue;
            }
            MarkerSpec spec = read(entry.getKey(), values);
            if (spec != null) {
                markers.add(spec);
            }
        }
        return List.copyOf(markers);
    }

    private static MarkerSpec read(String id, Map<?, ?> values) {
        Object world = values.get(WORLD);
        if (!(world instanceof String worldName) || !(values.get(X) instanceof Number x)
            || !(values.get(Y) instanceof Number y) || !(values.get(Z) instanceof Number z)) {
            return null;
        }
        Object label = values.get(LABEL);
        return MarkerSpec.at(id, worldName, x.doubleValue(), y.doubleValue(), z.doubleValue())
            .withLabel(label instanceof String text ? text : id)
            .asWaypoint();
    }

    private static String key(String name) {
        String normalized = Objects.requireNonNull(name, "name").trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 64) {
            throw new IllegalArgumentException("a personal marker name must be 1 to 64 characters");
        }
        return normalized;
    }
}
