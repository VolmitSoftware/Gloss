package art.arcane.gloss.drop;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.ParticleLayer;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record RealDropSettingsDoc(
    int schemaVersion,
    long revision,
    Presentation presentation,
    List<Variant> variants,
    Audience audience
) {
    public static final String KIND = "real-drops";
    public static final String DEFAULT_ID = "default";
    public static final int CURRENT_SCHEMA_VERSION = 3;

    public static final RealDropSettingsDoc DEFAULTS = new RealDropSettingsDoc(
        CURRENT_SCHEMA_VERSION,
        DocumentEnvelope.INITIAL_REVISION,
        null,
        null,
        null);

    public RealDropSettingsDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        presentation = presentation == null ? new Presentation(
            null, null, null, null, null, null, null, null, null, null) : presentation;
        variants = variants == null ? List.of() : List.copyOf(variants);
        validateVariants(variants);
        audience = audience == null ? new Audience(null) : audience;
    }

    public static RealDropSettingsDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, RealDropSettingsDoc.class);
    }

    public GlossConfig.RealDrops toConfig(boolean enabled) {
        return presentation.toConfig(enabled);
    }

    public record Presentation(
        Limits limits,
        Scale scale,
        Motion motion,
        Landing landing,
        Labels labels,
        Filters filters,
        Physics physics,
        Script script,
        Animation animation,
        List<ParticleLayer> particleLayers
    ) {
        public Presentation {
            limits = limits == null ? new Limits(null, null, null, null, null, null) : limits;
            scale = scale == null ? new Scale(null, null, null) : scale;
            motion = motion == null ? new Motion(
                null, null, null, null, null, null, null, null, null, null) : motion;
            landing = landing == null ? new Landing(
                null, null, null, null, null, null, null, null) : landing;
            labels = labels == null ? new Labels(
                null, null, null, null, null, null, null, null, null, null, null, null) : labels;
            filters = filters == null ? new Filters(null, null, null) : filters;
            physics = physics == null ? new Physics(null, null, null, null, null) : physics;
            script = script == null ? new Script(null, null, null, null, null, null, null) : script;
            animation = animation == null ? new Animation(null, null, null) : animation;
            particleLayers = ParticleLayer.copyLayers(particleLayers, "real-drop presentation");
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
                    motion.changeOnBounce(),
                    motion.velocityInfluence().floatValue(),
                    motion.submergedSpinMultiplier().floatValue(),
                    motion.groundRollMultiplier().floatValue()),
                new GlossConfig.RealDrops.Landing(
                    landing.mode(),
                    landing.tiltDegrees().floatValue(),
                    landing.randomYaw(),
                    landing.transitionTicks(),
                    landing.faceAttraction().floatValue(),
                    landing.movingFaceAttraction().floatValue(),
                    landing.alignmentDegrees().floatValue(),
                    landing.settleDelayTicks()),
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
                script.toConfig(),
                animation.toConfig(),
                particleLayers);
        }
    }

    public record Variant(String id, Integer priority, String when, Presentation presentation) {
        public Variant {
            id = normalizeId(id);
            priority = clamp(priority, -10000, 10000, 0);
            when = normalizeCondition(when, "variants." + id + ".when");
            presentation = Objects.requireNonNull(
                presentation, "real-drop variant presentation");
        }
    }

    public record Audience(String when) {
        public Audience {
            when = normalizeCondition(when == null ? "true" : when, "audience.when");
        }
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
        Boolean changeOnBounce,
        Double velocityInfluence,
        Double submergedSpinMultiplier,
        Double groundRollMultiplier
    ) {
        public Motion {
            tumble = tumble == null || tumble;
            speedMultiplier = clamp(speedMultiplier, 0.1D, 4.0D, 1.35D);
            degreesPerSecondX = clamp(degreesPerSecondX, -1440.0D, 1440.0D, 160.0D);
            degreesPerSecondY = clamp(degreesPerSecondY, -1440.0D, 1440.0D, 120.0D);
            degreesPerSecondZ = clamp(degreesPerSecondZ, -1440.0D, 1440.0D, 100.0D);
            variance = clamp(variance, 0.0D, 1.0D, 0.2D);
            changeOnBounce = changeOnBounce == null || changeOnBounce;
            velocityInfluence = clamp(velocityInfluence, 0.0D, 4.0D, 0.35D);
            submergedSpinMultiplier = clamp(submergedSpinMultiplier, 0.0D, 1.0D, 0.35D);
            groundRollMultiplier = clamp(groundRollMultiplier, 0.0D, 4.0D, 1.0D);
        }
    }

    public record Landing(
        String mode,
        Double tiltDegrees,
        Boolean randomYaw,
        Integer transitionTicks,
        Double faceAttraction,
        Double movingFaceAttraction,
        Double alignmentDegrees,
        Integer settleDelayTicks
    ) {
        public Landing {
            mode = choice(mode, "NATURAL", "FLAT", "UPRIGHT");
            tiltDegrees = clamp(tiltDegrees, 0.0D, 45.0D, 10.0D);
            randomYaw = randomYaw == null || randomYaw;
            transitionTicks = clamp(transitionTicks, 0, 20, 4);
            faceAttraction = clamp(faceAttraction, 0.0D, 1.0D, 0.55D);
            movingFaceAttraction = clamp(movingFaceAttraction, 0.0D, 1.0D, 0.15D);
            alignmentDegrees = clamp(alignmentDegrees, 0.05D, 10.0D, 0.5D);
            settleDelayTicks = clamp(settleDelayTicks, 0, 100, 4);
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

    public record MaterialProperties(Double glow, Double lightLevel) {
        public MaterialProperties {
            glow = clamp(glow, 0.0D, 4294967295.0D, 0.0D);
            lightLevel = clamp(lightLevel, 0.0D, 15.0D, 0.0D);
        }

        GlossConfig.RealDrops.MaterialProperties toConfig() {
            return new GlossConfig.RealDrops.MaterialProperties(glow, lightLevel);
        }
    }

    public record AnimationKeyframe(
        Double tick,
        Double value,
        String materialMap,
        String easing
    ) {
        public AnimationKeyframe {
            tick = clamp(tick, 0.0D, 1000000.0D, 0.0D);
            value = finite(value, 0.0D);
            materialMap = materialMap == null ? "" : materialMap.trim();
            easing = choice(easing, "LINEAR", "HOLD", "EASE_IN", "EASE_OUT", "EASE_IN_OUT", "BACK_OUT");
        }

        GlossConfig.RealDrops.AnimationKeyframe toConfig() {
            return new GlossConfig.RealDrops.AnimationKeyframe(
                tick,
                value,
                materialMap,
                GlossConfig.RealDrops.AnimationEasing.valueOf(easing));
        }
    }

    public record AnimationTrack(String target, String blend, List<AnimationKeyframe> keyframes) {
        public AnimationTrack {
            target = choice(target, "OFFSET_X", "OFFSET_Y", "OFFSET_Z", "ROTATION_X", "ROTATION_Y",
                "ROTATION_Z", "SCALE_X", "SCALE_Y", "SCALE_Z", "GLOW", "VISIBLE", "PHYSICS",
                "LIGHT_LEVEL");
            blend = choice(blend, "ADD", "REPLACE", "MULTIPLY");
            keyframes = keyframes == null ? List.of() : List.copyOf(keyframes);
        }

        GlossConfig.RealDrops.AnimationTrack toConfig() {
            List<GlossConfig.RealDrops.AnimationKeyframe> converted = new ArrayList<>(keyframes.size());
            for (AnimationKeyframe keyframe : keyframes) {
                if (keyframe != null) {
                    converted.add(keyframe.toConfig());
                }
            }
            return new GlossConfig.RealDrops.AnimationTrack(
                GlossConfig.RealDrops.AnimationTarget.valueOf(target),
                GlossConfig.RealDrops.AnimationBlend.valueOf(blend),
                List.copyOf(converted));
        }
    }

    public record AnimationClip(
        String trigger,
        Double durationTicks,
        Boolean loop,
        List<AnimationTrack> tracks
    ) {
        public AnimationClip {
            trigger = choice(trigger, "SPAWN", "AIRBORNE", "REBOUNDING", "ROLLING", "SLIDING", "SETTLING",
                "SETTLED", "SUBMERGED", "FLOATING", "IMPACT", "BOUNCE", "ENTER_FLUID", "EXIT_FLUID",
                "START_ROLL", "SETTLE", "WAKE");
            durationTicks = clamp(durationTicks, 0.0D, 1000000.0D, 0.0D);
            loop = loop != null && loop;
            tracks = tracks == null ? List.of() : List.copyOf(tracks);
        }

        GlossConfig.RealDrops.AnimationClip toConfig() {
            List<GlossConfig.RealDrops.AnimationTrack> converted = new ArrayList<>(tracks.size());
            for (AnimationTrack track : tracks) {
                if (track != null) {
                    converted.add(track.toConfig());
                }
            }
            return new GlossConfig.RealDrops.AnimationClip(
                GlossConfig.RealDrops.AnimationTrigger.valueOf(trigger),
                durationTicks,
                loop,
                List.copyOf(converted));
        }
    }

    public record AnimationProfile(
        String id,
        Integer priority,
        List<String> materials,
        List<AnimationClip> clips
    ) {
        public AnimationProfile {
            id = id == null || id.isBlank() ? "default" : id.trim();
            priority = clamp(priority, -10000, 10000, 0);
            materials = materials == null || materials.isEmpty() ? List.of("*") : clean(materials);
            clips = clips == null ? List.of() : List.copyOf(clips);
        }

        GlossConfig.RealDrops.AnimationProfile toConfig() {
            List<GlossConfig.RealDrops.AnimationClip> converted = new ArrayList<>(clips.size());
            for (AnimationClip clip : clips) {
                if (clip != null) {
                    converted.add(clip.toConfig());
                }
            }
            return new GlossConfig.RealDrops.AnimationProfile(
                id,
                priority,
                materials,
                List.copyOf(converted));
        }
    }

    public record Animation(
        Boolean enabled,
        Map<String, Map<String, MaterialProperties>> materialProperties,
        List<AnimationProfile> profiles
    ) {
        public Animation {
            enabled = enabled != null && enabled;
            materialProperties = cleanMaterialProperties(materialProperties);
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
            RealDropAnimationPlan.validate(config(enabled, materialProperties, profiles));
        }

        GlossConfig.RealDrops.RealDropAnimation toConfig() {
            return config(enabled, materialProperties, profiles);
        }

        private static GlossConfig.RealDrops.RealDropAnimation config(
            boolean enabled,
            Map<String, Map<String, MaterialProperties>> materialProperties,
            List<AnimationProfile> profiles
        ) {
            Map<String, Map<String, GlossConfig.RealDrops.MaterialProperties>> convertedProperties =
                new LinkedHashMap<>(materialProperties.size());
            for (Map.Entry<String, Map<String, MaterialProperties>> group : materialProperties.entrySet()) {
                Map<String, GlossConfig.RealDrops.MaterialProperties> converted =
                    new LinkedHashMap<>(group.getValue().size());
                for (Map.Entry<String, MaterialProperties> entry : group.getValue().entrySet()) {
                    converted.put(entry.getKey(), entry.getValue().toConfig());
                }
                convertedProperties.put(group.getKey(), Collections.unmodifiableMap(converted));
            }
            List<GlossConfig.RealDrops.AnimationProfile> convertedProfiles = new ArrayList<>(profiles.size());
            for (AnimationProfile profile : profiles) {
                if (profile != null) {
                    convertedProfiles.add(profile.toConfig());
                }
            }
            return new GlossConfig.RealDrops.RealDropAnimation(
                enabled,
                Collections.unmodifiableMap(convertedProperties),
                List.copyOf(convertedProfiles));
        }

        private static Map<String, Map<String, MaterialProperties>> cleanMaterialProperties(
            Map<String, Map<String, MaterialProperties>> source
        ) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            Map<String, Map<String, MaterialProperties>> cleaned = new LinkedHashMap<>(source.size());
            for (Map.Entry<String, Map<String, MaterialProperties>> group : source.entrySet()) {
                String name = group.getKey() == null ? "" : group.getKey().trim();
                if (name.isEmpty() || group.getValue() == null) {
                    continue;
                }
                Map<String, MaterialProperties> entries = new LinkedHashMap<>(group.getValue().size());
                for (Map.Entry<String, MaterialProperties> entry : group.getValue().entrySet()) {
                    String material = entry.getKey() == null ? "" : entry.getKey().trim();
                    if (!material.isEmpty() && entry.getValue() != null) {
                        entries.put(material, entry.getValue());
                    }
                }
                cleaned.put(name, Collections.unmodifiableMap(entries));
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

    private static double finite(Double value, double fallback) {
        return value == null || !Double.isFinite(value) ? fallback : value;
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

    private static void validateVariants(List<Variant> variants) {
        Set<String> ids = new HashSet<>(variants.size());
        for (Variant variant : variants) {
            if (variant == null) {
                throw new IllegalArgumentException("real-drop variants may not contain null entries");
            }
            if (!ids.add(variant.id())) {
                throw new IllegalArgumentException("duplicate real-drop variant id: " + variant.id());
            }
        }
    }

    private static String normalizeId(String id) {
        String normalized = id == null ? "" : id.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("real-drop variant id may not be blank");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (!Character.isLetterOrDigit(character)
                && character != '-' && character != '_' && character != '.') {
                throw new IllegalArgumentException("invalid real-drop variant id: " + normalized);
            }
        }
        return normalized;
    }

    private static String normalizeCondition(String when, String path) {
        String normalized = when == null ? "" : when.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(path + " may not be blank");
        }
        return normalized;
    }
}
