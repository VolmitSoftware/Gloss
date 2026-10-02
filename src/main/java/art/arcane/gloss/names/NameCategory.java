package art.arcane.gloss.names;

import java.util.Locale;

public enum NameCategory {
    MATERIALS("materials"),
    ENTITIES("entities"),
    WORLDS("worlds"),
    GAME_MODES("gameModes"),
    DIMENSIONS("dimensions"),
    DAMAGE_CAUSES("damageCauses"),
    EFFECTS("effects"),
    GROUPS("groups");

    private final String key;

    NameCategory(String key) {
        this.key = key;
    }

    public static NameCategory parse(String key) {
        for (NameCategory category : values()) {
            if (category.key.equals(key)) {
                return category;
            }
        }
        throw new IllegalArgumentException("Unknown names category: " + key);
    }

    public String key() {
        return key;
    }

    public String normalize(String source) {
        String normalized = source == null ? "" : source.trim();
        if (this == WORLDS) {
            return normalized;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        if (this == DIMENSIONS) {
            return switch (normalized) {
                case "normal" -> "overworld";
                case "nether" -> "the_nether";
                default -> normalized;
            };
        }
        return normalized;
    }
}
