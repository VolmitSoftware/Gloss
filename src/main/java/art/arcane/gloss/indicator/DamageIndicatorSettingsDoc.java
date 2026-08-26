package art.arcane.gloss.indicator;

import art.arcane.gloss.api.ParticleLayer;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record DamageIndicatorSettingsDoc(
    int schemaVersion,
    long revision,
    Limits limits,
    Style damage,
    Style healing,
    Audience audience
) {
    public static final String KIND = "damage-indicators";
    public static final String DEFAULT_ID = "default";
    public static final int CURRENT_SCHEMA_VERSION = 3;

    private static final String DEFAULT_AUDIENCE_PERMISSION = "gloss.indicators.show";
    private static final Limits DEFAULT_LIMITS = new Limits(null, null, null, null);
    private static final IndicatorPresentation DEFAULT_DAMAGE_PRESENTATION = new IndicatorPresentation(
        "&c&l{amount}",
        new Vector(0.0D, 0.7D, 0.0D),
        new Motion(0.8D, 1.3D, -0.93D, 0.0D),
        new Transform(1.0D, 0.82D, 0.68D),
        List.of());
    private static final IndicatorPresentation DEFAULT_HEALING_PRESENTATION = new IndicatorPresentation(
        "&a&l{amount}",
        new Vector(0.0D, -0.1D, 0.0D),
        new Motion(0.45D, 0.65D, 0.05D, 0.0D),
        new Transform(1.0D, 1.1D, 0.62D),
        List.of());
    private static final Style DEFAULT_DAMAGE = new Style(
        "true", DEFAULT_DAMAGE_PRESENTATION, List.of());
    private static final Style DEFAULT_HEALING = new Style(
        "true", DEFAULT_HEALING_PRESENTATION, List.of());
    private static final Audience DEFAULT_AUDIENCE = new Audience(
        "hasPermission('viewer', '" + DEFAULT_AUDIENCE_PERMISSION + "')");

    public static final DamageIndicatorSettingsDoc DEFAULTS = new DamageIndicatorSettingsDoc(
        CURRENT_SCHEMA_VERSION,
        DocumentEnvelope.INITIAL_REVISION,
        DEFAULT_LIMITS,
        DEFAULT_DAMAGE,
        DEFAULT_HEALING,
        DEFAULT_AUDIENCE);

    public DamageIndicatorSettingsDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        limits = limits == null ? DEFAULT_LIMITS : limits;
        damage = resolveStyle(damage, DEFAULT_DAMAGE);
        healing = resolveStyle(healing, DEFAULT_HEALING);
        audience = audience == null ? DEFAULT_AUDIENCE : resolveAudience(audience);
        validateCondition("damage.when", damage.when());
        validateVariants("damage.variants", damage.variants());
        validateCondition("healing.when", healing.when());
        validateVariants("healing.variants", healing.variants());
        validateCondition("audience.when", audience.when());
    }

    public static DamageIndicatorSettingsDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, DamageIndicatorSettingsDoc.class);
    }

    public record Limits(Integer maxPerSecond, Long lifetimeMs, Double minimumDelta, Integer decimals) {
        public Limits {
            maxPerSecond = clamp(maxPerSecond, 1, 1000, 40);
            lifetimeMs = clamp(lifetimeMs, 250L, 30000L, 3000L);
            minimumDelta = clamp(minimumDelta, 0.0D, 1000.0D, 0.009D);
            decimals = clamp(decimals, 0, 4, 0);
        }
    }

    public record Style(String when, IndicatorPresentation presentation, List<Variant> variants) {
        public Style {
            when = normalizeCondition(when);
            variants = variants == null ? List.of() : List.copyOf(variants);
        }
    }

    public record Variant(String id, int priority, String when, IndicatorPresentation presentation) {
        public Variant {
            id = requireId(id);
            when = requireCondition(when);
            if (presentation == null || !presentation.complete()) {
                throw new IllegalArgumentException(
                    "damage-indicator variant presentation must be complete");
            }
        }
    }

    public record IndicatorPresentation(String format, Vector offset, Motion motion,
                                        Transform transform, List<ParticleLayer> particleLayers) {
        public IndicatorPresentation {
            if (format != null && !format.contains("{amount}")) {
                throw new IllegalArgumentException(
                    "damage-indicator format must contain the {amount} token");
            }
            offset = normalizeOffset(offset);
            particleLayers = particleLayers == null ? null
                : ParticleLayer.copyLayers(particleLayers, "damage-indicator presentation");
        }

        @Override
        public Vector offset() {
            return offset == null ? null : offset.clone();
        }

        private boolean complete() {
            return format != null && offset != null && motion != null && motion.complete()
                && transform != null && transform.complete();
        }
    }

    public record Motion(Double horizontalSpeed, Double verticalSpeed, Double verticalAcceleration,
                         Double spinDegreesPerSecond) {
        public Motion {
            horizontalSpeed = clampNullable(horizontalSpeed, 0.0D, 16.0D);
            verticalSpeed = clampNullable(verticalSpeed, -16.0D, 16.0D);
            verticalAcceleration = clampNullable(verticalAcceleration, -32.0D, 32.0D);
            spinDegreesPerSecond = clampNullable(spinDegreesPerSecond, -1440.0D, 1440.0D);
        }

        private boolean complete() {
            return horizontalSpeed != null && verticalSpeed != null
                && verticalAcceleration != null && spinDegreesPerSecond != null;
        }
    }

    public record Transform(Double startScale, Double endScale, Double fadeStartFraction) {
        public Transform {
            startScale = clampNullable(startScale, 0.0D, 16.0D);
            endScale = clampNullable(endScale, 0.0D, 16.0D);
            fadeStartFraction = clampNullable(fadeStartFraction, 0.0D, 1.0D);
        }

        private boolean complete() {
            return startScale != null && endScale != null && fadeStartFraction != null;
        }
    }

    public record Audience(String when) {
        public Audience {
            when = normalizeCondition(when);
        }
    }

    private static Style resolveStyle(Style source, Style defaults) {
        if (source == null) {
            return defaults;
        }
        return new Style(
            source.when() == null ? defaults.when() : source.when(),
            resolvePresentation(source.presentation(), defaults.presentation()),
            source.variants());
    }

    private static Audience resolveAudience(Audience source) {
        return new Audience(source.when() == null ? DEFAULT_AUDIENCE.when() : source.when());
    }

    private static IndicatorPresentation resolvePresentation(
        IndicatorPresentation source, IndicatorPresentation defaults) {
        if (source == null) {
            return defaults;
        }
        Motion motion = resolveMotion(source.motion(), defaults.motion());
        Transform transform = resolveTransform(source.transform(), defaults.transform());
        return new IndicatorPresentation(
            source.format() == null ? defaults.format() : source.format(),
            source.offset() == null ? defaults.offset() : source.offset(),
            motion,
            transform,
            source.particleLayers() == null ? defaults.particleLayers() : source.particleLayers());
    }

    private static Motion resolveMotion(Motion source, Motion defaults) {
        if (source == null) {
            return defaults;
        }
        return new Motion(
            source.horizontalSpeed() == null ? defaults.horizontalSpeed() : source.horizontalSpeed(),
            source.verticalSpeed() == null ? defaults.verticalSpeed() : source.verticalSpeed(),
            source.verticalAcceleration() == null
                ? defaults.verticalAcceleration() : source.verticalAcceleration(),
            source.spinDegreesPerSecond() == null
                ? defaults.spinDegreesPerSecond() : source.spinDegreesPerSecond());
    }

    private static Transform resolveTransform(Transform source, Transform defaults) {
        if (source == null) {
            return defaults;
        }
        return new Transform(
            source.startScale() == null ? defaults.startScale() : source.startScale(),
            source.endScale() == null ? defaults.endScale() : source.endScale(),
            source.fadeStartFraction() == null
                ? defaults.fadeStartFraction() : source.fadeStartFraction());
    }

    private static void validateVariants(String path, List<Variant> variants) {
        Set<String> ids = new HashSet<String>(variants.size());
        for (int index = 0; index < variants.size(); index++) {
            Variant variant = variants.get(index);
            if (variant == null) {
                throw new IllegalArgumentException(path + " must not contain null entries");
            }
            if (!ids.add(variant.id())) {
                throw new IllegalArgumentException(path + " contains duplicate id: " + variant.id());
            }
            validateCondition(path + "[" + index + "].when", variant.when());
        }
    }

    private static void validateCondition(String path, String expression) {
        ConditionCompiler.compile(new ConditionSource(KIND + "/default.json." + path, expression));
    }

    private static String normalizeCondition(String value) {
        return value == null ? null : requireCondition(value);
    }

    private static String requireCondition(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("damage-indicator condition must not be blank");
        }
        return value.trim();
    }

    private static String requireId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("damage-indicator variant id must not be blank");
        }
        String id = value.trim();
        for (int index = 0; index < id.length(); index++) {
            char character = id.charAt(index);
            if (!Character.isLetterOrDigit(character)
                && character != '-' && character != '_' && character != '.') {
                throw new IllegalArgumentException("invalid damage-indicator variant id: " + id);
            }
        }
        return id;
    }

    private static int clamp(Integer value, int minimum, int maximum, int fallback) {
        int resolved = value == null ? fallback : value;
        return Math.max(minimum, Math.min(maximum, resolved));
    }

    private static long clamp(Long value, long minimum, long maximum, long fallback) {
        long resolved = value == null ? fallback : value;
        return Math.max(minimum, Math.min(maximum, resolved));
    }

    private static double clamp(Double value, double minimum, double maximum, double fallback) {
        double resolved = value == null || !Double.isFinite(value) ? fallback : value;
        return Math.max(minimum, Math.min(maximum, resolved));
    }

    private static Double clampNullable(Double value, double minimum, double maximum) {
        if (value == null) {
            return null;
        }
        if (!Double.isFinite(value)) {
            return minimum;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static Vector normalizeOffset(Vector value) {
        if (value == null) {
            return null;
        }
        return new Vector(
            clamp(value.getX(), -32.0D, 32.0D, 0.0D),
            clamp(value.getY(), -32.0D, 32.0D, 0.0D),
            clamp(value.getZ(), -32.0D, 32.0D, 0.0D));
    }
}
