package art.arcane.gloss.camera;

import art.arcane.gloss.state.PlayerSections;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

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
                        String gameMode) {
    }

    private final PlayerSections sections;

    public CameraJournal(PlayerSections sections) {
        this.sections = Objects.requireNonNull(sections, "sections");
    }

    public void write(Player player, Location at, GameMode gameMode) {
        write(player.getUniqueId(), at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
            at.getYaw(), at.getPitch(), gameMode.name());
    }

    public void write(UUID player, String world, double x, double y, double z, float yaw, float pitch,
                      String gameMode) {
        Map<String, Object> values = new LinkedHashMap<>(7);
        values.put(WORLD, world);
        values.put(X, x);
        values.put(Y, y);
        values.put(Z, z);
        values.put(YAW, (double) yaw);
        values.put(PITCH, (double) pitch);
        values.put(GAME_MODE, gameMode);
        sections.write(player, SECTION, values);
    }

    public Optional<Entry> read(UUID player) {
        Map<String, Object> values = sections.read(player, SECTION);
        if (!(values.get(WORLD) instanceof String world) || !(values.get(X) instanceof Number x)
            || !(values.get(Y) instanceof Number y) || !(values.get(Z) instanceof Number z)) {
            return Optional.empty();
        }
        return Optional.of(new Entry(world, x.doubleValue(), y.doubleValue(), z.doubleValue(),
            number(values.get(YAW)), number(values.get(PITCH)),
            values.get(GAME_MODE) instanceof String mode ? mode : GameMode.SURVIVAL.name()));
    }

    public void clear(UUID player) {
        sections.write(player, SECTION, Map.of());
    }

    private static float number(Object value) {
        return value instanceof Number number ? number.floatValue() : 0.0F;
    }
}
