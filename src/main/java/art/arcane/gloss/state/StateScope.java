package art.arcane.gloss.state;

import java.util.Locale;

/** Where one state key lives: one value per player, per world, or one for the whole server. */
public enum StateScope {
    PLAYER,
    WORLD,
    GLOBAL;

    public static StateScope parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("state scope must be player, world, or global");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "player" -> PLAYER;
            case "world" -> WORLD;
            case "global" -> GLOBAL;
            default -> throw new IllegalArgumentException("state scope must be player, world, or global: " + value);
        };
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
