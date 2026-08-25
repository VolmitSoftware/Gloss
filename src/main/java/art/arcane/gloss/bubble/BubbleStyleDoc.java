package art.arcane.gloss.bubble;

import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import org.bukkit.util.Vector;

import java.util.Locale;

public record BubbleStyleDoc(int schemaVersion, long revision, String prefix, Vector offset, int wordWrapChars,
                             long maxAliveMs, boolean followPlayer, boolean hideOwn, Motion motion,
                             Shimmer shimmer, Select select) {
    public static final String KIND = "bubbles";
    public static final int CURRENT_SCHEMA_VERSION = 3;
    public static final String DEFAULT_TRANSLATION_Y =
        "10 * pow(clamp((ageMs - lifetimeMs + 2000) / 2000, 0, 1), 16)";

    private static final Axis ZERO = new Axis("0", "0", "0");
    private static final Axis ONE = new Axis("1", "1", "1");
    private static final Axis DEFAULT_TRANSLATION = new Axis("0", DEFAULT_TRANSLATION_Y, "0");
    private static final Motion DEFAULT_MOTION = new Motion(DEFAULT_TRANSLATION, ONE, ZERO, "1");
    private static final Shimmer DEFAULT_SHIMMER = new Shimmer(true, true, Shimmer.DEFAULT_COLOR,
        3, BubbleShimmerPlan.DEFAULT_DURATION_MS, BubbleShimmerPlan.DEFAULT_SPAWN_DELAY_MS,
        BubbleShimmerPlan.DEFAULT_FLY_AWAY_LEAD_MS);

    public static final BubbleStyleDoc DEFAULTS = new BubbleStyleDoc(CURRENT_SCHEMA_VERSION,
        DocumentEnvelope.INITIAL_REVISION, "&7", new Vector(0.0D, 0.3D, 0.0D), 32, 5000L,
        true, true, DEFAULT_MOTION, DEFAULT_SHIMMER, null);

    public BubbleStyleDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        prefix = prefix == null ? "&7" : prefix;
        offset = offset == null ? new Vector(0.0D, 0.3D, 0.0D) : offset.clone();
        wordWrapChars = clampInt(wordWrapChars, 8, 128);
        maxAliveMs = Math.max(500L, Math.min(60000L, maxAliveMs));
        motion = motion == null ? DEFAULT_MOTION : motion;
        shimmer = shimmer == null ? DEFAULT_SHIMMER : shimmer;
    }

    public static BubbleStyleDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, BubbleStyleDoc.class);
    }

    private static int clampInt(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record Axis(String x, String y, String z) {
        private Axis withDefaults(Axis defaults) {
            return new Axis(x == null ? defaults.x : x, y == null ? defaults.y : y, z == null ? defaults.z : z);
        }
    }

    public record Motion(Axis translation, Axis scale, Axis rotation, String opacity) {
        public Motion {
            translation = translation == null ? DEFAULT_TRANSLATION : translation.withDefaults(DEFAULT_TRANSLATION);
            scale = scale == null ? ONE : scale.withDefaults(ONE);
            rotation = rotation == null ? ZERO : rotation.withDefaults(ZERO);
            opacity = opacity == null ? "1" : opacity;
            BubbleMotionPlan.validate(translation, scale, rotation, opacity);
        }
    }

    public record Shimmer(Boolean spawn, Boolean flyAway, String color, Integer width, Long durationMs,
                          Long spawnDelayMs, Long flyAwayLeadMs) {
        public static final String DEFAULT_COLOR = "#ffffff";

        public static final int MIN_WIDTH = 1;
        public static final int MAX_WIDTH = 16;
        public static final long MIN_DURATION_MS = 100L;
        public static final long MAX_DURATION_MS = 10000L;
        public static final long MAX_OFFSET_MS = 60000L;

        public Shimmer {
            spawn = spawn == null ? true : spawn;
            flyAway = flyAway == null ? true : flyAway;
            color = normalizeColor(color, DEFAULT_COLOR, "color");
            width = clampInt(width == null ? 3 : width, MIN_WIDTH, MAX_WIDTH);
            durationMs = clampLong(durationMs == null ? BubbleShimmerPlan.DEFAULT_DURATION_MS : durationMs,
                MIN_DURATION_MS, MAX_DURATION_MS);
            spawnDelayMs = clampLong(spawnDelayMs == null
                ? BubbleShimmerPlan.DEFAULT_SPAWN_DELAY_MS : spawnDelayMs, 0L, MAX_OFFSET_MS);
            flyAwayLeadMs = clampLong(flyAwayLeadMs == null
                ? BubbleShimmerPlan.DEFAULT_FLY_AWAY_LEAD_MS : flyAwayLeadMs, 0L, MAX_OFFSET_MS);
        }

        private static String normalizeColor(String value, String fallback, String key) {
            String normalized = value == null ? fallback : value.trim().toLowerCase(Locale.ROOT);
            if (!isRgbColor(normalized)) {
                throw new IllegalArgumentException("Bubble shimmer " + key + " must be #RRGGBB.");
            }
            return normalized;
        }

        private static boolean isRgbColor(String value) {
            if (value.length() != 7 || value.charAt(0) != '#') {
                return false;
            }
            for (int index = 1; index < value.length(); index++) {
                char digit = value.charAt(index);
                if (!isHexDigit(digit)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isHexDigit(char value) {
            return value >= '0' && value <= '9' || value >= 'a' && value <= 'f';
        }

        private static long clampLong(long value, long minimum, long maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }
    }

    public record Select(int priority, String when) {
        public Select {
            when = when == null ? "" : when.trim();
            if (when.isEmpty()) {
                throw new IllegalArgumentException("Bubble style selection condition may not be blank.");
            }
            ConditionCompiler.compile(new ConditionSource("bubbles.select.when", when));
        }
    }
}
