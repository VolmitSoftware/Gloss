package art.arcane.gloss.drop;

import art.arcane.gloss.GlossConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class RealDropAnimationPlan {
    private static final int TARGET_COUNT = GlossConfig.RealDrops.AnimationTarget.values().length;

    private final boolean enabled;
    private final List<CompiledProfile> profiles;
    private final Map<String, MaterialMap> materialProperties;

    private RealDropAnimationPlan(GlossConfig.RealDrops.RealDropAnimation animation) {
        enabled = animation != null && animation.enabled();
        materialProperties = compileMaterialProperties(animation == null ? Map.of() : animation.materialProperties());
        profiles = compileProfiles(animation == null ? List.of() : animation.profiles());
    }

    static RealDropAnimationPlan compile(GlossConfig.RealDrops.RealDropAnimation animation) {
        return new RealDropAnimationPlan(animation);
    }

    static void validate(GlossConfig.RealDrops.RealDropAnimation animation) {
        new RealDropAnimationPlan(animation);
    }

    boolean enabled() {
        return enabled;
    }

    AnimationSample sample(String material, GlossConfig.RealDrops.AnimationTrigger trigger, double elapsedTicks) {
        return sample(material, List.of(new ActiveClip(trigger, elapsedTicks)));
    }

    AnimationSample sample(String material, List<ActiveClip> activeClips) {
        if (!enabled || activeClips == null || activeClips.isEmpty()) {
            return AnimationSample.neutral("");
        }
        String normalizedMaterial = normalizeMaterial(material);
        CompiledProfile profile = profile(normalizedMaterial);
        if (profile == null) {
            return AnimationSample.neutral("");
        }
        double[] values = neutralValues();
        for (ActiveClip active : activeClips) {
            if (active == null || active.trigger() == null) {
                continue;
            }
            List<CompiledClip> clips = profile.clips().get(active.trigger());
            if (clips == null) {
                continue;
            }
            for (CompiledClip clip : clips) {
                clip.apply(normalizedMaterial, active.elapsedTicks(), values, materialProperties);
            }
        }
        return sample(profile.id(), values);
    }

    String profileId(String material) {
        CompiledProfile profile = profile(normalizeMaterial(material));
        return profile == null ? "" : profile.id();
    }

    double clipDurationTicks(String material, GlossConfig.RealDrops.AnimationTrigger trigger) {
        CompiledProfile profile = profile(normalizeMaterial(material));
        if (profile == null || trigger == null) {
            return -1.0D;
        }
        List<CompiledClip> clips = profile.clips().get(trigger);
        if (clips == null || clips.isEmpty()) {
            return -1.0D;
        }
        double duration = 0.0D;
        for (CompiledClip clip : clips) {
            duration = Math.max(duration, clip.durationTicks());
        }
        return duration;
    }

    boolean requiresContinuousUpdates(
        String material,
        GlossConfig.RealDrops.AnimationTrigger trigger,
        double elapsedTicks
    ) {
        if (!enabled || trigger == null) {
            return false;
        }
        String normalizedMaterial = normalizeMaterial(material);
        CompiledProfile profile = profile(normalizedMaterial);
        if (profile == null) {
            return false;
        }
        List<CompiledClip> clips = profile.clips().get(trigger);
        if (clips == null) {
            return false;
        }
        for (CompiledClip clip : clips) {
            if (clip.requiresContinuousUpdates(
                normalizedMaterial,
                elapsedTicks,
                materialProperties)) {
                return true;
            }
        }
        return false;
    }

    private CompiledProfile profile(String material) {
        for (CompiledProfile profile : profiles) {
            if (profile.matches(material)) {
                return profile;
            }
        }
        return null;
    }

    private static Map<String, MaterialMap> compileMaterialProperties(
        Map<String, Map<String, GlossConfig.RealDrops.MaterialProperties>> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, MaterialMap> compiled = new LinkedHashMap<>(source.size());
        for (Map.Entry<String, Map<String, GlossConfig.RealDrops.MaterialProperties>> group : source.entrySet()) {
            String name = group.getKey() == null ? "" : group.getKey().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("animation material property map has no name");
            }
            if (compiled.containsKey(name)) {
                throw new IllegalArgumentException("animation material property map " + name + " is declared twice");
            }
            List<MaterialEntry> entries = new ArrayList<>();
            Map<String, GlossConfig.RealDrops.MaterialProperties> values = group.getValue();
            if (values != null) {
                for (Map.Entry<String, GlossConfig.RealDrops.MaterialProperties> entry : values.entrySet()) {
                    if (entry.getValue() == null) {
                        continue;
                    }
                    entries.add(new MaterialEntry(pattern(entry.getKey()), entry.getValue()));
                }
            }
            compiled.put(name, new MaterialMap(List.copyOf(entries)));
        }
        return Map.copyOf(compiled);
    }

    private List<CompiledProfile> compileProfiles(List<GlossConfig.RealDrops.AnimationProfile> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<CompiledProfile> compiled = new ArrayList<>(source.size());
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < source.size(); index++) {
            GlossConfig.RealDrops.AnimationProfile profile = source.get(index);
            if (profile == null) {
                continue;
            }
            String id = profile.id() == null ? "" : profile.id().trim();
            if (id.isEmpty()) {
                throw new IllegalArgumentException("animation profile has no id");
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException("animation profile " + id + " is declared twice");
            }
            List<Pattern> materials = new ArrayList<>();
            List<String> materialPatterns = profile.materials();
            if (materialPatterns == null || materialPatterns.isEmpty()) {
                materials.add(pattern("*"));
            } else {
                for (String material : materialPatterns) {
                    materials.add(pattern(material));
                }
            }
            Map<GlossConfig.RealDrops.AnimationTrigger, List<CompiledClip>> clips = compileClips(profile.clips());
            compiled.add(new CompiledProfile(
                id,
                profile.priority(),
                index,
                List.copyOf(materials),
                clips));
        }
        compiled.sort(Comparator.comparingInt(CompiledProfile::priority).reversed()
            .thenComparingInt(CompiledProfile::order));
        return List.copyOf(compiled);
    }

    private Map<GlossConfig.RealDrops.AnimationTrigger, List<CompiledClip>> compileClips(
        List<GlossConfig.RealDrops.AnimationClip> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<GlossConfig.RealDrops.AnimationTrigger, List<CompiledClip>> grouped =
            new EnumMap<>(GlossConfig.RealDrops.AnimationTrigger.class);
        for (GlossConfig.RealDrops.AnimationClip clip : source) {
            if (clip == null) {
                continue;
            }
            if (clip.trigger() == null) {
                throw new IllegalArgumentException("animation clip has no trigger");
            }
            if (!Double.isFinite(clip.durationTicks()) || clip.durationTicks() < 0.0D) {
                throw new IllegalArgumentException("animation clip durationTicks must be finite and at least zero");
            }
            List<CompiledTrack> tracks = compileTracks(clip);
            grouped.computeIfAbsent(clip.trigger(), ignored -> new ArrayList<>())
                .add(new CompiledClip(clip.durationTicks(), clip.loop(), tracks));
        }
        Map<GlossConfig.RealDrops.AnimationTrigger, List<CompiledClip>> immutable =
            new EnumMap<>(GlossConfig.RealDrops.AnimationTrigger.class);
        for (Map.Entry<GlossConfig.RealDrops.AnimationTrigger, List<CompiledClip>> entry : grouped.entrySet()) {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(immutable);
    }

    private List<CompiledTrack> compileTracks(GlossConfig.RealDrops.AnimationClip clip) {
        List<GlossConfig.RealDrops.AnimationTrack> source = clip.tracks();
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<CompiledTrack> tracks = new ArrayList<>(source.size());
        for (GlossConfig.RealDrops.AnimationTrack track : source) {
            if (track == null) {
                continue;
            }
            validateBlend(track.target(), track.blend());
            List<GlossConfig.RealDrops.AnimationKeyframe> keyframes = track.keyframes();
            if (keyframes == null || keyframes.isEmpty()) {
                throw new IllegalArgumentException("animation track " + track.target() + " has no keyframes");
            }
            List<CompiledKeyframe> compiled = new ArrayList<>(keyframes.size());
            Set<Double> ticks = new HashSet<>();
            for (GlossConfig.RealDrops.AnimationKeyframe keyframe : keyframes) {
                if (keyframe == null || !Double.isFinite(keyframe.tick()) || keyframe.tick() < 0.0D
                    || keyframe.tick() > clip.durationTicks()) {
                    throw new IllegalArgumentException(
                        "animation track " + track.target() + " has a keyframe outside its clip duration");
                }
                if (!Double.isFinite(keyframe.value())) {
                    throw new IllegalArgumentException(
                        "animation track " + track.target() + " has a non-finite keyframe value");
                }
                if (!ticks.add(keyframe.tick())) {
                    throw new IllegalArgumentException(
                        "animation track " + track.target() + " has two keyframes at tick " + keyframe.tick());
                }
                String materialMap = keyframe.materialMap() == null ? "" : keyframe.materialMap().trim();
                if (!materialMap.isEmpty()) {
                    validateMaterialMap(track.target(), materialMap);
                }
                GlossConfig.RealDrops.AnimationEasing easing = keyframe.easing() == null
                    ? GlossConfig.RealDrops.AnimationEasing.LINEAR
                    : keyframe.easing();
                compiled.add(new CompiledKeyframe(
                    keyframe.tick(),
                    keyframe.value(),
                    materialMap,
                    easing));
            }
            compiled.sort(Comparator.comparingDouble(CompiledKeyframe::tick));
            tracks.add(new CompiledTrack(track.target(), track.blend(), List.copyOf(compiled)));
        }
        return List.copyOf(tracks);
    }

    private void validateMaterialMap(GlossConfig.RealDrops.AnimationTarget target, String materialMap) {
        if (target != GlossConfig.RealDrops.AnimationTarget.GLOW
            && target != GlossConfig.RealDrops.AnimationTarget.LIGHT_LEVEL) {
            throw new IllegalArgumentException(
                "animation material property maps only support GLOW and LIGHT_LEVEL tracks");
        }
        if (!materialProperties.containsKey(materialMap)) {
            throw new IllegalArgumentException("animation material property map " + materialMap + " does not exist");
        }
    }

    private static void validateBlend(
        GlossConfig.RealDrops.AnimationTarget target,
        GlossConfig.RealDrops.AnimationBlend blend
    ) {
        if (target == null || blend == null) {
            throw new IllegalArgumentException("animation track target and blend are required");
        }
        boolean valid = switch (blend) {
            case REPLACE -> true;
            case ADD -> isOffset(target) || isRotation(target);
            case MULTIPLY -> isScale(target);
        };
        if (!valid) {
            throw new IllegalArgumentException("animation blend " + blend + " is not valid for " + target);
        }
    }

    private static boolean isOffset(GlossConfig.RealDrops.AnimationTarget target) {
        return target == GlossConfig.RealDrops.AnimationTarget.OFFSET_X
            || target == GlossConfig.RealDrops.AnimationTarget.OFFSET_Y
            || target == GlossConfig.RealDrops.AnimationTarget.OFFSET_Z;
    }

    private static boolean isRotation(GlossConfig.RealDrops.AnimationTarget target) {
        return target == GlossConfig.RealDrops.AnimationTarget.ROTATION_X
            || target == GlossConfig.RealDrops.AnimationTarget.ROTATION_Y
            || target == GlossConfig.RealDrops.AnimationTarget.ROTATION_Z;
    }

    private static boolean isScale(GlossConfig.RealDrops.AnimationTarget target) {
        return target == GlossConfig.RealDrops.AnimationTarget.SCALE_X
            || target == GlossConfig.RealDrops.AnimationTarget.SCALE_Y
            || target == GlossConfig.RealDrops.AnimationTarget.SCALE_Z;
    }

    private static AnimationSample sample(String profileId, double[] values) {
        return new AnimationSample(
            profileId,
            values[GlossConfig.RealDrops.AnimationTarget.OFFSET_X.ordinal()],
            values[GlossConfig.RealDrops.AnimationTarget.OFFSET_Y.ordinal()],
            values[GlossConfig.RealDrops.AnimationTarget.OFFSET_Z.ordinal()],
            values[GlossConfig.RealDrops.AnimationTarget.ROTATION_X.ordinal()],
            values[GlossConfig.RealDrops.AnimationTarget.ROTATION_Y.ordinal()],
            values[GlossConfig.RealDrops.AnimationTarget.ROTATION_Z.ordinal()],
            clamp(values[GlossConfig.RealDrops.AnimationTarget.SCALE_X.ordinal()], 0.0D, 16.0D),
            clamp(values[GlossConfig.RealDrops.AnimationTarget.SCALE_Y.ordinal()], 0.0D, 16.0D),
            clamp(values[GlossConfig.RealDrops.AnimationTarget.SCALE_Z.ordinal()], 0.0D, 16.0D),
            Math.round(values[GlossConfig.RealDrops.AnimationTarget.GLOW.ordinal()]) & 0xFFFFFFFFL,
            values[GlossConfig.RealDrops.AnimationTarget.VISIBLE.ordinal()] >= 0.5D,
            values[GlossConfig.RealDrops.AnimationTarget.PHYSICS.ordinal()] >= 0.5D,
            (int) Math.round(clamp(
                values[GlossConfig.RealDrops.AnimationTarget.LIGHT_LEVEL.ordinal()], 0.0D, 15.0D)));
    }

    private static double[] neutralValues() {
        double[] values = new double[TARGET_COUNT];
        values[GlossConfig.RealDrops.AnimationTarget.SCALE_X.ordinal()] = 1.0D;
        values[GlossConfig.RealDrops.AnimationTarget.SCALE_Y.ordinal()] = 1.0D;
        values[GlossConfig.RealDrops.AnimationTarget.SCALE_Z.ordinal()] = 1.0D;
        values[GlossConfig.RealDrops.AnimationTarget.VISIBLE.ordinal()] = 1.0D;
        values[GlossConfig.RealDrops.AnimationTarget.PHYSICS.ordinal()] = 1.0D;
        return values;
    }

    private static Pattern pattern(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("animation material pattern must not be blank");
        }
        String normalized = normalizeMaterial(source);
        StringBuilder regex = new StringBuilder(normalized.length() * 2 + 2);
        regex.append('^');
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (character == '*') {
                regex.append(".*");
            } else if (character == '?') {
                regex.append('.');
            } else {
                if ("\\.^$|()[]{}+".indexOf(character) >= 0) {
                    regex.append('\\');
                }
                regex.append(character);
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString());
    }

    private static String normalizeMaterial(String material) {
        String normalized = material == null ? "" : material.trim();
        int namespace = normalized.indexOf(':');
        if (namespace >= 0) {
            normalized = normalized.substring(namespace + 1);
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private static double ease(GlossConfig.RealDrops.AnimationEasing easing, double progress) {
        double selected = clamp(progress, 0.0D, 1.0D);
        return switch (easing) {
            case HOLD -> 0.0D;
            case LINEAR -> selected;
            case EASE_IN -> selected * selected * selected;
            case EASE_OUT -> 1.0D - Math.pow(1.0D - selected, 3.0D);
            case EASE_IN_OUT -> selected < 0.5D
                ? 4.0D * selected * selected * selected
                : 1.0D - Math.pow(-2.0D * selected + 2.0D, 3.0D) / 2.0D;
            case BACK_OUT -> {
                double shifted = selected - 1.0D;
                yield 1.0D + 2.70158D * shifted * shifted * shifted + 1.70158D * shifted * shifted;
            }
        };
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    record ActiveClip(GlossConfig.RealDrops.AnimationTrigger trigger, double elapsedTicks) {
    }

    record AnimationSample(
        String profileId,
        double offsetX,
        double offsetY,
        double offsetZ,
        double rotationX,
        double rotationY,
        double rotationZ,
        double scaleX,
        double scaleY,
        double scaleZ,
        long glowArgb,
        boolean visible,
        boolean physics,
        int lightLevel
    ) {
        static AnimationSample neutral(String profileId) {
            return new AnimationSample(
                profileId,
                0.0D,
                0.0D,
                0.0D,
                0.0D,
                0.0D,
                0.0D,
                1.0D,
                1.0D,
                1.0D,
                0L,
                true,
                true,
                0);
        }
    }

    private record CompiledProfile(
        String id,
        int priority,
        int order,
        List<Pattern> materials,
        Map<GlossConfig.RealDrops.AnimationTrigger, List<CompiledClip>> clips
    ) {
        boolean matches(String material) {
            for (Pattern pattern : materials) {
                if (pattern.matcher(material).matches()) {
                    return true;
                }
            }
            return false;
        }
    }

    private record CompiledClip(double durationTicks, boolean loop, List<CompiledTrack> tracks) {
        void apply(String material, double elapsedTicks, double[] values, Map<String, MaterialMap> properties) {
            double tick = Math.max(0.0D, elapsedTicks);
            if (loop && durationTicks > 0.0D) {
                tick %= durationTicks;
            } else {
                tick = Math.min(durationTicks, tick);
            }
            for (CompiledTrack track : tracks) {
                track.apply(material, tick, values, properties);
            }
        }

        boolean requiresContinuousUpdates(
            String material,
            double elapsedTicks,
            Map<String, MaterialMap> properties
        ) {
            if (!loop && elapsedTicks >= durationTicks) {
                return false;
            }
            double tick = Math.max(0.0D, elapsedTicks);
            if (loop && durationTicks > 0.0D) {
                tick %= durationTicks;
            }
            for (CompiledTrack track : tracks) {
                if (track.requiresContinuousUpdates(material, tick, properties)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record CompiledTrack(
        GlossConfig.RealDrops.AnimationTarget target,
        GlossConfig.RealDrops.AnimationBlend blend,
        List<CompiledKeyframe> keyframes
    ) {
        void apply(String material, double tick, double[] values, Map<String, MaterialMap> properties) {
            double value = value(material, tick, properties);
            int index = target.ordinal();
            values[index] = switch (blend) {
                case ADD -> values[index] + value;
                case REPLACE -> value;
                case MULTIPLY -> values[index] * value;
            };
        }

        private double value(String material, double tick, Map<String, MaterialMap> properties) {
            CompiledKeyframe first = keyframes.getFirst();
            if (tick <= first.tick() || keyframes.size() == 1) {
                return first.value(material, target, properties);
            }
            CompiledKeyframe previous = first;
            for (int index = 1; index < keyframes.size(); index++) {
                CompiledKeyframe next = keyframes.get(index);
                if (tick <= next.tick()) {
                    double span = next.tick() - previous.tick();
                    double progress = span <= 0.0D ? 1.0D : (tick - previous.tick()) / span;
                    double eased = ease(next.easing(), progress);
                    double from = previous.value(material, target, properties);
                    double to = next.value(material, target, properties);
                    return from + (to - from) * eased;
                }
                previous = next;
            }
            return keyframes.getLast().value(material, target, properties);
        }

        private boolean requiresContinuousUpdates(
            String material,
            double tick,
            Map<String, MaterialMap> properties
        ) {
            if (keyframes.size() < 2 || tick < keyframes.getFirst().tick() || tick >= keyframes.getLast().tick()) {
                return false;
            }
            CompiledKeyframe previous = keyframes.getFirst();
            for (int index = 1; index < keyframes.size(); index++) {
                CompiledKeyframe next = keyframes.get(index);
                if (tick < next.tick()) {
                    if (next.easing() == GlossConfig.RealDrops.AnimationEasing.HOLD) {
                        return false;
                    }
                    return previous.value(material, target, properties)
                        != next.value(material, target, properties);
                }
                previous = next;
            }
            return false;
        }
    }

    private record CompiledKeyframe(
        double tick,
        double value,
        String materialMap,
        GlossConfig.RealDrops.AnimationEasing easing
    ) {
        double value(
            String material,
            GlossConfig.RealDrops.AnimationTarget target,
            Map<String, MaterialMap> properties
        ) {
            if (materialMap.isEmpty()) {
                return value;
            }
            MaterialMap map = properties.get(materialMap);
            return map == null ? value : map.value(material, target, value);
        }
    }

    private record MaterialMap(List<MaterialEntry> entries) {
        double value(String material, GlossConfig.RealDrops.AnimationTarget target, double fallback) {
            for (MaterialEntry entry : entries) {
                if (!entry.pattern().matcher(material).matches()) {
                    continue;
                }
                return switch (target) {
                    case GLOW -> entry.properties().glow();
                    case LIGHT_LEVEL -> entry.properties().lightLevel();
                    default -> fallback;
                };
            }
            return fallback;
        }
    }

    private record MaterialEntry(Pattern pattern, GlossConfig.RealDrops.MaterialProperties properties) {
    }
}
