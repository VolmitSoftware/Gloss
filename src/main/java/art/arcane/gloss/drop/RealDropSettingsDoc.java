package art.arcane.gloss.drop;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record RealDropSettingsDoc(
    int schemaVersion,
    long revision,
    Limits limits,
    Scale scale,
    Motion motion,
    Landing landing,
    Labels labels,
    Filters filters,
    Physics physics,
    Script script
) {
    public static final String KIND = "real-drops";
    public static final String DEFAULT_ID = "default";
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static final RealDropSettingsDoc DEFAULTS = new RealDropSettingsDoc(
        CURRENT_SCHEMA_VERSION,
        DocumentEnvelope.INITIAL_REVISION,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);

    public RealDropSettingsDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        limits = limits == null ? new Limits(null, null, null, null, null, null) : limits;
        scale = scale == null ? new Scale(null, null, null) : scale;
        motion = motion == null ? new Motion(null, null, null, null, null, null, null) : motion;
        landing = landing == null ? new Landing(null, null, null, null) : landing;
        labels = labels == null ? new Labels(null, null, null, null, null, null, null, null,
            null, null, null, null) : labels;
        filters = filters == null ? new Filters(null, null, null) : filters;
        physics = physics == null ? new Physics(null, null, null, null, null) : physics;
        script = script == null ? new Script(null, null, null, null, null, null, null) : script;
    }

    public static RealDropSettingsDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, RealDropSettingsDoc.class);
    }

    public GlossConfig.RealDrops toConfig(boolean enabled) {
        return new GlossConfig.RealDrops(
            enabled,
            new GlossConfig.RealDrops.Limits(
                limits.updateIntervalTicks(),
                limits.settledPollIntervalTicks(),
                limits.maxVisualsPerStack(),
                limits.maxVisualsPerChunk(),
                limits.viewRange().floatValue(),
                limits.spread().floatValue()),
            new GlossConfig.RealDrops.Scale(
                scale.defaultScale().floatValue(),
                scale.flatItems().floatValue(),
                scale.thinBlocks().floatValue()),
            new GlossConfig.RealDrops.Motion(
                motion.tumble(),
                motion.speedMultiplier().floatValue(),
                motion.degreesPerSecondX().floatValue(),
                motion.degreesPerSecondY().floatValue(),
                motion.degreesPerSecondZ().floatValue(),
                motion.variance().floatValue(),
                motion.changeOnBounce()),
            new GlossConfig.RealDrops.Landing(
                landing.mode(),
                landing.tiltDegrees().floatValue(),
                landing.randomYaw(),
                landing.transitionTicks()),
            new GlossConfig.RealDrops.Labels(
                labels.enabled(),
                labels.yOffset().floatValue(),
                labels.scale().floatValue(),
                labels.viewRange().floatValue(),
                labels.billboard(),
                labels.seeThrough(),
                labels.shadow(),
                labels.background(),
                labels.backgroundRed(),
                labels.backgroundGreen(),
                labels.backgroundBlue(),
                labels.backgroundAlpha()),
            new GlossConfig.RealDrops.Filters(
                filters.disabledWorlds(),
                filters.materialBlacklist(),
                filters.onlyPlayerDrops()),
            new GlossConfig.RealDrops.Physics(
                physics.enabled(),
                physics.gravityMultiplier().floatValue(),
                physics.bounce().floatValue(),
                physics.waterBuoyancy().floatValue(),
                physics.waterDrag().floatValue()),
            script.toConfig());
    }

    public record Limits(
        Integer updateIntervalTicks,
        Integer settledPollIntervalTicks,
        Integer maxVisualsPerStack,
        Integer maxVisualsPerChunk,
        Double viewRange,
        Double spread
    ) {
        public Limits {
            updateIntervalTicks = clamp(updateIntervalTicks, 1, 20, 2);
            settledPollIntervalTicks = clamp(settledPollIntervalTicks, 2, 200, 20);
            maxVisualsPerStack = clamp(maxVisualsPerStack, 1, 5, 3);
            maxVisualsPerChunk = clamp(maxVisualsPerChunk, 8, 1024, 128);
            viewRange = clamp(viewRange, 4.0D, 128.0D, 32.0D);
            spread = clamp(spread, 0.0D, 1.0D, 0.18D);
        }
    }

    public record Scale(Double defaultScale, Double flatItems, Double thinBlocks) {
        public Scale {
            defaultScale = clamp(defaultScale, 0.05D, 2.0D, 0.4D);
            flatItems = clamp(flatItems, 0.05D, 2.0D, 0.65D);
            thinBlocks = clamp(thinBlocks, 0.05D, 2.0D, 0.45D);
        }
    }

    public record Motion(
        Boolean tumble,
        Double speedMultiplier,
        Double degreesPerSecondX,
        Double degreesPerSecondY,
        Double degreesPerSecondZ,
        Double variance,
        Boolean changeOnBounce
    ) {
        public Motion {
            tumble = tumble == null || tumble;
            speedMultiplier = clamp(speedMultiplier, 0.1D, 4.0D, 1.35D);
            degreesPerSecondX = clamp(degreesPerSecondX, -1440.0D, 1440.0D, 160.0D);
            degreesPerSecondY = clamp(degreesPerSecondY, -1440.0D, 1440.0D, 120.0D);
            degreesPerSecondZ = clamp(degreesPerSecondZ, -1440.0D, 1440.0D, 100.0D);
            variance = clamp(variance, 0.0D, 1.0D, 0.2D);
            changeOnBounce = changeOnBounce == null || changeOnBounce;
        }
    }

    public record Landing(String mode, Double tiltDegrees, Boolean randomYaw, Integer transitionTicks) {
        public Landing {
            mode = choice(mode, "NATURAL", "FLAT", "UPRIGHT");
            tiltDegrees = clamp(tiltDegrees, 0.0D, 45.0D, 10.0D);
            randomYaw = randomYaw == null || randomYaw;
            transitionTicks = clamp(transitionTicks, 0, 20, 4);
        }
    }

    public record Labels(
        Boolean enabled,
        Double yOffset,
        Double scale,
        Double viewRange,
        String billboard,
        Boolean seeThrough,
        Boolean shadow,
        Boolean background,
        Integer backgroundRed,
        Integer backgroundGreen,
        Integer backgroundBlue,
        Integer backgroundAlpha
    ) {
        public Labels {
            enabled = enabled == null || enabled;
            yOffset = clamp(yOffset, 0.0D, 4.0D, 0.55D);
            scale = clamp(scale, 0.1D, 4.0D, 0.85D);
            viewRange = clamp(viewRange, 4.0D, 128.0D, 32.0D);
            billboard = choice(billboard, "CENTER", "FIXED", "HORIZONTAL", "VERTICAL");
            seeThrough = seeThrough == null || seeThrough;
            shadow = shadow == null || shadow;
            background = background == null || background;
            backgroundRed = clamp(backgroundRed, 0, 255, 0);
            backgroundGreen = clamp(backgroundGreen, 0, 255, 0);
            backgroundBlue = clamp(backgroundBlue, 0, 255, 0);
            backgroundAlpha = clamp(backgroundAlpha, 0, 255, 80);
        }
    }

    public record Filters(List<String> disabledWorlds, List<String> materialBlacklist,
                          Boolean onlyPlayerDrops) {
        public Filters {
            disabledWorlds = clean(disabledWorlds);
            materialBlacklist = materialBlacklist == null
                ? List.of("BEDROCK", "BARRIER")
                : clean(materialBlacklist);
            onlyPlayerDrops = onlyPlayerDrops != null && onlyPlayerDrops;
        }
    }

    public record Physics(
        Boolean enabled,
        Double gravityMultiplier,
        Double bounce,
        Double waterBuoyancy,
        Double waterDrag
    ) {
        public Physics {
            enabled = enabled != null && enabled;
            gravityMultiplier = clamp(gravityMultiplier, 0.0D, 4.0D, 1.0D);
            bounce = clamp(bounce, 0.0D, 0.9D, 0.0D);
            waterBuoyancy = clamp(waterBuoyancy, 0.0D, 1.0D, 0.0D);
            waterDrag = clamp(waterDrag, 0.0D, 1.0D, 0.0D);
        }
    }

    public record Axis(String x, String y, String z) {
        Axis withDefaults(Axis defaults) {
            return new Axis(
                blank(x) ? defaults.x : x.trim(),
                blank(y) ? defaults.y : y.trim(),
                blank(z) ? defaults.z : z.trim());
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    public record Script(
        Boolean enabled,
        Map<String, String> vars,
        Axis offset,
        Axis rotation,
        Axis scale,
        String glow,
        String visible
    ) {
        public static final int MAX_VARS = 32;

        private static final Axis ZERO = new Axis("0", "0", "0");
        private static final Axis ONE = new Axis("1", "1", "1");

        public Script {
            enabled = enabled != null && enabled;
            vars = cleanVars(vars);
            offset = offset == null ? ZERO : offset.withDefaults(ZERO);
            rotation = rotation == null ? ZERO : rotation.withDefaults(ZERO);
            scale = scale == null ? ONE : scale.withDefaults(ONE);
            glow = glow == null ? "" : glow.trim();
            visible = visible == null || visible.isBlank() ? "true" : visible.trim();
            RealDropScriptPlan.validate(config(enabled, vars, offset, rotation, scale, glow, visible));
        }

        GlossConfig.RealDrops.Script toConfig() {
            return config(enabled, vars, offset, rotation, scale, glow, visible);
        }

        private static GlossConfig.RealDrops.Script config(boolean enabled, Map<String, String> vars,
                                                           Axis offset, Axis rotation, Axis scale,
                                                           String glow, String visible) {
            List<GlossConfig.RealDrops.ScriptVar> declared = new ArrayList<>(vars.size());
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                declared.add(new GlossConfig.RealDrops.ScriptVar(entry.getKey(), entry.getValue()));
            }
            return new GlossConfig.RealDrops.Script(
                enabled,
                List.copyOf(declared),
                axis(offset),
                axis(rotation),
                axis(scale),
                glow,
                visible);
        }

        private static GlossConfig.RealDrops.Axis axis(Axis source) {
            return new GlossConfig.RealDrops.Axis(source.x(), source.y(), source.z());
        }

        private static Map<String, String> cleanVars(Map<String, String> values) {
            if (values == null || values.isEmpty()) {
                return Map.of();
            }
            if (values.size() > MAX_VARS) {
                throw new IllegalArgumentException(
                    "script.vars declares " + values.size() + " variables; the limit is " + MAX_VARS);
            }
            Map<String, String> cleaned = new LinkedHashMap<>(values.size());
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String name = entry.getKey() == null ? "" : entry.getKey().trim();
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("script.vars declares an entry with no name");
                }
                String source = entry.getValue() == null ? "" : entry.getValue().trim();
                if (source.isEmpty()) {
                    throw new IllegalArgumentException("script.vars." + name + " must be a non-blank expression");
                }
                cleaned.put(name, source);
            }
            return Collections.unmodifiableMap(cleaned);
        }
    }

    private static int clamp(Integer value, int minimum, int maximum, int fallback) {
        int selected = value == null ? fallback : value;
        return Math.max(minimum, Math.min(maximum, selected));
    }

    private static double clamp(Double value, double minimum, double maximum, double fallback) {
        double selected = value == null || !Double.isFinite(value) ? fallback : value;
        return Math.max(minimum, Math.min(maximum, selected));
    }

    private static String choice(String value, String... allowed) {
        String normalized = value == null ? allowed[0] : value.trim().toUpperCase(Locale.ROOT);
        for (String option : allowed) {
            if (option.equals(normalized)) {
                return option;
            }
        }
        return allowed[0];
    }

    private static List<String> clean(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>(values.size());
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim());
            }
        }
        return List.copyOf(cleaned);
    }
}
