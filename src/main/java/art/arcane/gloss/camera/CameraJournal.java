package art.arcane.gloss.camera;

import art.arcane.gloss.state.PlayerSections;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a rider really was before the camera took over. A ride is journalled before the player is
 * moved, so a crash mid-ride is recoverable: the next join reads the journal, puts the player
 * back, and clears it.
 */
public final class CameraJournal {
    public static final String SECTION = "camera";

    private static final String WORLD = "world";
    private static final String X = "x";
    private static final String Y = "y";
    private static final String Z = "z";
    private static final String YAW = "yaw";
    private static final String PITCH = "pitch";
    private static final String GAME_MODE = "gameMode";

    public record Entry(String world, double x, double y, double z, float yaw, float pitch,
                        String gameMode, Boolean allowFlight, Boolean flying, Vector velocity) {
    }

    private final PlayerSections sections;

    public CameraJournal(PlayerSections sections) {
        this.sections = Objects.requireNonNull(sections, "sections");
    }

    public void write(Player player, Location at, GameMode gameMode) {
        Map<String, Object> values = values(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
            at.getYaw(), at.getPitch(), gameMode.name());
        values.put("allowFlight", player.getAllowFlight());
        values.put("flying", player.isFlying());
        Vector velocity = player.getVelocity();
        values.put("velocityX", velocity.getX());
        values.put("velocityY", velocity.getY());
        values.put("velocityZ", velocity.getZ());
        persist(player.getUniqueId(), values);
    }

    public void write(UUID player, String world, double x, double y, double z, float yaw, float pitch,
                      String gameMode) {
        persist(player, values(world, x, y, z, yaw, pitch, gameMode));
    }

    private static Map<String, Object> values(String world, double x, double y, double z, float yaw,
                                             float pitch, String gameMode) {
        Map<String, Object> values = new LinkedHashMap<>(12);
        values.put(WORLD, world);
        values.put(X, x);
        values.put(Y, y);
        values.put(Z, z);
        values.put(YAW, (double) yaw);
        values.put(PITCH, (double) pitch);
        values.put(GAME_MODE, gameMode);
        return values;
    }

    public Optional<Entry> read(UUID player) {
        Map<String, Object> values = sections.read(player, SECTION);
        if (!(values.get(WORLD) instanceof String world) || !(values.get(X) instanceof Number x)
            || !(values.get(Y) instanceof Number y) || !(values.get(Z) instanceof Number z)) {
            return Optional.empty();
        }
        return Optional.of(new Entry(world, x.doubleValue(), y.doubleValue(), z.doubleValue(),
            number(values.get(YAW)), number(values.get(PITCH)),
            values.get(GAME_MODE) instanceof String mode ? mode : GameMode.SURVIVAL.name(),
            values.get("allowFlight") instanceof Boolean flight ? flight : null,
            values.get("flying") instanceof Boolean flying ? flying : null,
            new Vector(number(values.get("velocityX")), number(values.get("velocityY")),
                number(values.get("velocityZ")))));
    }

    public void clear(UUID player) {
        persist(player, Map.of());
    }

    private void persist(UUID player, Map<String, Object> values) {
        try {
            sections.writeChecked(player, SECTION, values);
        } catch (IOException failure) {
            throw new UncheckedIOException("Camera recovery state could not be saved for " + player, failure);
        }
    }

    private static float number(Object value) {
        return value instanceof Number number ? number.floatValue() : 0.0F;
    }
}
