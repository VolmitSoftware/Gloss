package art.arcane.gloss.entity;

import art.arcane.gloss.api.HologramBox;
import art.arcane.gloss.api.ParticleLayer;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.api.IconBillboard;
import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.particle.ParticleText;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record EntityOverlayDoc(
    int schemaVersion, long revision, Boolean enabled, Double range, Integer updateIntervalTicks,
    Integer maxEntitiesPerViewer, Boolean includePlayers, Double verticalOffset,
    Integer healthSegments, Long hitHighlightMs, List<String> blacklistWorlds,
    List<String> excludedEntityTypes, ShowCondition show, List<Line> lines,
    IconDisplayStyle style, HologramBox box, List<ParticleLayer> particleLayers
) {
    public static final String KIND = "entity-overlays";
    public static final String DEFAULT_ID = "default";
    public static final int CURRENT_SCHEMA_VERSION = 2;
    public static final int MAX_LINES = 64;
    public static final IconDisplayStyle DEFAULT_STYLE = new IconDisplayStyle(
        IconBillboard.CENTER, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, 0.75F, 0.75F, 0.75F);
    public static final List<Line> DEFAULT_LINES = List.of(
        new Line("name", "text", "&f{name}", ShowCondition.of("entity.named")),
        new Line("health", "text", "{bar} &f{health}&7/{max_health}", null),
        new Line("stack", "text", "&7x{count}", ShowCondition.of("entity.stackCount > 1")),
        new Line("damage", "text", "&c-{damage}", ShowCondition.of("entity.damaged")),
        new Line("insight", "insight", "{insight}", null),
        new Line("stats", "text", "&7ATK &f{attack} &8| &7ARM &f{armor}", null));
    public static final EntityOverlayDoc DEFAULTS = new EntityOverlayDoc(
        CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null);

    public EntityOverlayDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        enabled = enabled == null || enabled;
        range = number(range, 1, 64, 16);
        updateIntervalTicks = integer(updateIntervalTicks, 1, 40, 5);
        maxEntitiesPerViewer = integer(maxEntitiesPerViewer, 1, 256, 64);
        includePlayers = includePlayers == null || includePlayers;
        verticalOffset = number(verticalOffset, -2, 8, 0.35);
        healthSegments = integer(healthSegments, 1, 40, 10);
        hitHighlightMs = hitHighlightMs == null ? 750L : Math.clamp(hitHighlightMs, 0L, 10000L);
        blacklistWorlds = blacklistWorlds == null ? List.of() : List.copyOf(blacklistWorlds);
        excludedEntityTypes = excludedEntityTypes == null ? List.of("ARMOR_STAND")
            : excludedEntityTypes.stream().map(value -> value.toUpperCase(Locale.ROOT)).toList();
        show = show == null ? ShowCondition.ALWAYS : show;
        lines = lines == null ? DEFAULT_LINES : copyLines(lines);
        style = style == null ? DEFAULT_STYLE : style;
        box = box == null ? HologramBox.defaults() : box;
        particleLayers = ParticleLayer.copyLayers(particleLayers, KIND);
    }

    public static EntityOverlayDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, EntityOverlayDoc.class);
    }

    private static List<Line> copyLines(List<Line> lines) {
        if (lines.size() > MAX_LINES) {
            throw new IllegalArgumentException("Entity overlays may declare at most " + MAX_LINES + " lines");
        }
        Set<String> ids = new HashSet<>(lines.size());
        for (Line line : lines) {
            Objects.requireNonNull(line, "Entity overlay lines must not contain null entries");
            if (!ids.add(line.id())) {
                throw new IllegalArgumentException("Duplicate entity overlay line id: " + line.id());
            }
        }
        return List.copyOf(lines);
    }

    private static double number(Double value, double minimum, double maximum, double fallback) {
        return value == null || !Double.isFinite(value) ? fallback : Math.clamp(value, minimum, maximum);
    }

    private static int integer(Integer value, int minimum, int maximum, int fallback) {
        return value == null ? fallback : Math.clamp(value, minimum, maximum);
    }

    public record Line(String id, String type, String text, ShowCondition show) {
        public Line {
            id = Objects.requireNonNull(id, "Entity overlay line id").trim();
            if (id.length() > 64 || !id.matches("[a-z0-9][a-z0-9._-]*")) {
                throw new IllegalArgumentException("Entity overlay line id must match [a-z0-9][a-z0-9._-]* and be at most 64 characters");
            }
            type = type == null ? "text" : type;
            if (!type.equals("text") && !type.equals("insight") && !type.equals("spacer")) {
                throw new IllegalArgumentException("Entity overlay line type must be text, insight, or spacer");
            }
            text = text == null ? (type.equals("insight") ? "{insight}" : "") : text;
            if (text.length() > 4096) {
                throw new IllegalArgumentException("Entity overlay line text must not exceed 4096 characters");
            }
            ParticleText.parse(text);
            show = show == null ? ShowCondition.ALWAYS : show;
        }
    }
}
