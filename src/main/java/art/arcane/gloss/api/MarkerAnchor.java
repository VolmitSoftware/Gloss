package art.arcane.gloss.api;

import org.bukkit.Location;

import java.util.UUID;

/**
 * Where a marker or waypoint sits: a fixed world position, an entity it follows, or a player it
 * follows. Exactly one of the three is set; a document that names none or more than one is refused
 * at load.
 */
public record MarkerAnchor(String world, Double x, Double y, Double z, UUID entity, String player) {
    public MarkerAnchor {
        world = world == null || world.isBlank() ? null : world.trim();
        player = player == null || player.isBlank() ? null : player.trim();
        boolean hasPosition = world != null || x != null || y != null || z != null;
        int targets = (hasPosition ? 1 : 0) + (entity == null ? 0 : 1) + (player == null ? 0 : 1);
        if (targets != 1) {
            throw new IllegalArgumentException(
                "anchor must name exactly one of a world position, an entity or a player");
        }
        if (hasPosition && (world == null || !finite(x) || !finite(y) || !finite(z))) {
            throw new IllegalArgumentException("a position anchor requires world, x, y and z");
        }
    }

    public static MarkerAnchor position(String world, double x, double y, double z) {
        return new MarkerAnchor(world, x, y, z, null, null);
    }

    public static MarkerAnchor of(Location location) {
        return position(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
    }

    public static MarkerAnchor entity(UUID entity) {
        return new MarkerAnchor(null, null, null, null, entity, null);
    }

    public static MarkerAnchor player(String player) {
        return new MarkerAnchor(null, null, null, null, null, player);
    }

    public boolean isPosition() {
        return world != null;
    }

    public boolean followsEntity() {
        return entity != null;
    }

    public boolean followsPlayer() {
        return player != null;
    }

    private static boolean finite(Double value) {
        return value != null && Double.isFinite(value);
    }
}
