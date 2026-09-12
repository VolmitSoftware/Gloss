package art.arcane.gloss.zone;

import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.marker.Audience;
import art.arcane.gloss.marker.MarkerColors;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record ZoneDoc(int schemaVersion, long revision, ShowCondition show, ZoneShape shape,
                      Render render, Ambience ambience, String toggle, Audience audience) {
    public static final String KIND = "zones";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String DEFAULT_TOGGLE = "gloss.zones.toggle";
    public static final Set<String> RENDER_MODES = Set.of("particles", "walls", "hybrid");
    /** Blocks at which hybrid mode switches from wall panels to particle outlines. */
    public static final double HYBRID_WALL_DISTANCE = 16.0D;

    public ZoneDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        show = show == null ? ShowCondition.ALWAYS : show;
        shape = Objects.requireNonNull(shape, "a zone requires a shape");
        render = render == null ? Render.defaults() : render;
        ambience = ambience == null ? Ambience.off() : ambience;
        toggle = toggle == null || toggle.isBlank() ? DEFAULT_TOGGLE : toggle.trim();
        audience = audience == null ? Audience.ALWAYS : audience;
    }

    public static ZoneDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, ZoneDoc.class);
    }

    public record Render(String mode, String particle, String color, Double spacing, String wallMaterial,
                         Boolean facingOnly, Boolean edgesOnly) {
        public Render {
            mode = mode == null || mode.isBlank() ? "particles" : mode.trim().toLowerCase(Locale.ROOT);
            if (!RENDER_MODES.contains(mode)) {
                throw new IllegalArgumentException("zone render mode must be one of "
                    + String.join(", ", RENDER_MODES));
            }
            particle = particle == null || particle.isBlank() ? "minecraft:dust" : particle.trim();
            MarkerColors.parse(color, "zone render color");
            spacing = spacing == null ? 0.75D : Math.clamp(spacing, 0.05D, 8.0D);
            wallMaterial = wallMaterial == null || wallMaterial.isBlank()
                ? "minecraft:white_stained_glass" : wallMaterial.trim();
            facingOnly = facingOnly == null || facingOnly;
            edgesOnly = edgesOnly != null && edgesOnly;
        }

        public static Render defaults() {
            return new Render(null, null, null, null, null, null, null);
        }

        public int rgb() {
            return MarkerColors.parse(color, "zone render color");
        }
    }

    public record Ambience(Boolean enabled, String particle, Integer perViewerPerTick, Double radius,
                           ShowCondition when) {
        public Ambience {
            enabled = enabled != null && enabled;
            particle = particle == null || particle.isBlank() ? "minecraft:ash" : particle.trim();
            perViewerPerTick = perViewerPerTick == null ? 4 : Math.clamp(perViewerPerTick, 0, 64);
            radius = radius == null ? 12.0D : Math.clamp(radius, 1.0D, 64.0D);
            when = when == null ? ShowCondition.ALWAYS : when;
        }

        public static Ambience off() {
            return new Ambience(false, null, null, null, null);
        }
    }
}
