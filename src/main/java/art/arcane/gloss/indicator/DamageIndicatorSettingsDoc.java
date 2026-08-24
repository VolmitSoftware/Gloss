package art.arcane.gloss.indicator;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

public record DamageIndicatorSettingsDoc(
    int schemaVersion,
    long revision,
    Limits limits,
    Style damage,
    Style healing,
    Filters filters
) {
    public static final String KIND = "damage-indicators";
    public static final String DEFAULT_ID = "default";
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final Limits DEFAULT_LIMITS = new Limits(null, null, null, null);
    private static final Style DEFAULT_DAMAGE = new Style(
        true,
        "&c&l{amount}",
        new Vector(0.0D, 0.7D, 0.0D),
        new Motion(0.8D, 1.3D, -0.93D, 0.0D),
        new Presentation(1.0D, 0.82D, 0.68D));
    private static final Style DEFAULT_HEALING = new Style(
        true,
        "&a&l{amount}",
        new Vector(0.0D, -0.1D, 0.0D),
        new Motion(0.45D, 0.65D, 0.05D, 0.0D),
        new Presentation(1.0D, 1.1D, 0.62D));
    private static final Filters DEFAULT_FILTERS = new Filters(null);

    public static final DamageIndicatorSettingsDoc DEFAULTS = new DamageIndicatorSettingsDoc(
        CURRENT_SCHEMA_VERSION,
        DocumentEnvelope.INITIAL_REVISION,
        DEFAULT_LIMITS,
        DEFAULT_DAMAGE,
        DEFAULT_HEALING,
        DEFAULT_FILTERS);

    public DamageIndicatorSettingsDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        limits = limits == null ? DEFAULT_LIMITS : limits;
        damage = resolveStyle(damage, DEFAULT_DAMAGE);
        healing = resolveStyle(healing, DEFAULT_HEALING);
        filters = filters == null ? DEFAULT_FILTERS : filters;
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

    public record Style(Boolean enabled, String format, Vector offset, Motion motion,
                        Presentation presentation) {
        public Style {
            if (format != null && !format.contains("{amount}")) {
                throw new IllegalArgumentException(
                    "damage-indicator format must contain the {amount} token");
            }
            offset = normalizeOffset(offset);
        }

        @Override
        public Vector offset() {
            return offset.clone();
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
    }

    public record Presentation(Double startScale, Double endScale, Double fadeStartFraction) {
        public Presentation {
            startScale = clampNullable(startScale, 0.0D, 16.0D);
            endScale = clampNullable(endScale, 0.0D, 16.0D);
            fadeStartFraction = clampNullable(fadeStartFraction, 0.0D, 1.0D);
        }
    }

    public record Filters(List<String> disabledWorlds) {
        public Filters {
            if (disabledWorlds == null) {
                disabledWorlds = List.of();
            } else {
                List<String> cleaned = new ArrayList<>(disabledWorlds.size());
                for (String world : disabledWorlds) {
                    if (world != null && !world.isBlank()) {
                        String normalized = world.trim();
                        if (!cleaned.contains(normalized)) {
                            cleaned.add(normalized);
                        }
                    }
                }
                disabledWorlds = List.copyOf(cleaned);
            }
        }
    }

    private static Style resolveStyle(Style source, Style defaults) {
        if (source == null) {
            return defaults;
        }
        Motion motion = resolveMotion(source.motion, defaults.motion);
        Presentation presentation = resolvePresentation(source.presentation, defaults.presentation);
        return new Style(
            source.enabled == null ? defaults.enabled : source.enabled,
            source.format == null ? defaults.format : source.format,
            source.offset == null ? defaults.offset : source.offset,
            motion,
            presentation);
    }

    private static Motion resolveMotion(Motion source, Motion defaults) {
        if (source == null) {
            return defaults;
        }
        return new Motion(
            source.horizontalSpeed == null ? defaults.horizontalSpeed : source.horizontalSpeed,
            source.verticalSpeed == null ? defaults.verticalSpeed : source.verticalSpeed,
            source.verticalAcceleration == null ? defaults.verticalAcceleration : source.verticalAcceleration,
            source.spinDegreesPerSecond == null ? defaults.spinDegreesPerSecond : source.spinDegreesPerSecond);
    }

    private static Presentation resolvePresentation(Presentation source, Presentation defaults) {
        if (source == null) {
            return defaults;
        }
        return new Presentation(
            source.startScale == null ? defaults.startScale : source.startScale,
            source.endScale == null ? defaults.endScale : source.endScale,
            source.fadeStartFraction == null ? defaults.fadeStartFraction : source.fadeStartFraction);
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
