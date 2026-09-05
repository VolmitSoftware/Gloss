package art.arcane.gloss.entity;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;

import java.util.List;
import java.util.Locale;

public record EntityOverlayDoc(
    int schemaVersion, long revision, Boolean enabled, Double range, Integer updateIntervalTicks,
    Integer maxEntitiesPerViewer, Boolean includePlayers, Double verticalOffset, Double scale,
    Integer healthSegments, Boolean showHealthNumbers, Boolean showNames, Boolean showCombatStats,
    Long hitHighlightMs, List<String> blacklistWorlds, List<String> excludedEntityTypes,
    String nameFormat, String healthFormat, String stackFormat, String statsFormat, String damageFormat
) {
    public static final String KIND = "entity-overlays";
    public static final String DEFAULT_ID = "default";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final EntityOverlayDoc DEFAULTS = new EntityOverlayDoc(
        CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    public EntityOverlayDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        enabled = enabled == null || enabled;
        range = number(range, 1, 64, 16);
        updateIntervalTicks = integer(updateIntervalTicks, 1, 40, 5);
        maxEntitiesPerViewer = integer(maxEntitiesPerViewer, 1, 256, 64);
        includePlayers = includePlayers == null || includePlayers;
        verticalOffset = number(verticalOffset, -2, 8, 0.35);
        scale = number(scale, 0.1, 4, 0.75);
        healthSegments = integer(healthSegments, 1, 40, 10);
        showHealthNumbers = showHealthNumbers == null || showHealthNumbers;
        showNames = showNames == null || showNames;
        showCombatStats = showCombatStats == null || showCombatStats;
        hitHighlightMs = hitHighlightMs == null ? 750L : Math.clamp(hitHighlightMs, 0L, 10000L);
        blacklistWorlds = blacklistWorlds == null ? List.of() : List.copyOf(blacklistWorlds);
        excludedEntityTypes = excludedEntityTypes == null ? List.of("ARMOR_STAND")
            : excludedEntityTypes.stream().map(value -> value.toUpperCase(Locale.ROOT)).toList();
        nameFormat = text(nameFormat, "&f{name}");
        healthFormat = text(healthFormat, "{bar} &f{health}&7/{max_health}");
        stackFormat = text(stackFormat, " &7x{count}");
        statsFormat = text(statsFormat, "&7ATK &f{attack} &8| &7ARM &f{armor}");
        damageFormat = text(damageFormat, "&c-{damage}");
    }

    public static EntityOverlayDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, EntityOverlayDoc.class);
    }

    private static double number(Double value, double minimum, double maximum, double fallback) {
        return value == null || !Double.isFinite(value) ? fallback : Math.clamp(value, minimum, maximum);
    }

    private static int integer(Integer value, int minimum, int maximum, int fallback) {
        return value == null ? fallback : Math.clamp(value, minimum, maximum);
    }

    private static String text(String value, String fallback) {
        if (value != null && value.length() > 1024) {
            throw new IllegalArgumentException("Entity overlay formats must not exceed 1024 characters");
        }
        return value == null ? fallback : value;
    }
}
