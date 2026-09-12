package art.arcane.gloss.animation.clip;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class ClipPlan {
    private static final int TARGET_COUNT = Target.values().length;
    private static final String NO_MATERIAL = "";
    private static final CompiledProfile NO_PROFILE =
        new CompiledProfile("", 0, 0, List.of(), Map.of(), Map.of());

    private final boolean enabled;
    private final List<CompiledProfile> profiles;
    private final Map<String, MaterialMap> materialProperties;
    private final Map<String, CompiledProfile> profileMemo = new ConcurrentHashMap<>();

    private ClipPlan(ClipSet set) {
        enabled = set != null && set.enabled();
        materialProperties = compileMaterialProperties(set == null ? Map.of() : set.materialProperties());
        profiles = compileProfiles(set == null ? List.of() : set.profiles());
    }

    public static ClipPlan compile(ClipSet set) {
        return new ClipPlan(set);
    }

    public static void validate(ClipSet set) {
        new ClipPlan(set);
    }

    public boolean enabled() {
        return enabled;
    }

    public ClipSample sample(String material, Trigger trigger, double elapsedTicks) {
        return sample(material, List.of(new ActiveClip(trigger, elapsedTicks)));
    }

    public ClipSample sample(String material, List<ActiveClip> activeClips) {
        if (!enabled || activeClips == null || activeClips.isEmpty()) {
            return ClipSample.NEUTRAL;
        }
        String normalizedMaterial = normalizeMaterial(material);
        CompiledProfile profile = profile(normalizedMaterial);
        if (profile == null) {
            return ClipSample.NEUTRAL;
        }
        double[] values = neutralValues();
        applyClips(profile, normalizedMaterial, activeClips, values, null);
        return sample(profile.id(), values);
    }

    public Map<String, ClipSample> sampleBones(Collection<String> bones, List<ActiveClip> activeClips) {
        Map<String, ClipSample> samples = new HashMap<>(Math.max(1, bones.size()) * 2);
        CompiledProfile profile = enabled && activeClips != null && !activeClips.isEmpty()
            ? profile(NO_MATERIAL)
            : null;
        for (String bone : bones) {
            if (profile == null) {
                samples.put(bone, ClipSample.NEUTRAL);
                continue;
            }
            double[] values = neutralValues();
            boolean touched = applyClips(profile, NO_MATERIAL, activeClips, values, bone);
            samples.put(bone, touched ? sample(profile.id(), values) : ClipSample.NEUTRAL);
        }
        return samples;
    }

    public String profileId(String material) {
        CompiledProfile profile = profile(normalizeMaterial(material));
        return profile == null ? "" : profile.id();
    }

    public double clipDurationTicks(String material, Trigger trigger) {
        CompiledProfile profile = profile(normalizeMaterial(material));
        if (profile == null || trigger == null) {
            return -1.0D;
        }
        Double duration = profile.durations().get(trigger);
        return duration == null ? -1.0D : duration;
    }

    public boolean requiresContinuousUpdates(String material, Trigger trigger, double elapsedTicks) {
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
            if (clip.requiresContinuousUpdates(normalizedMaterial, elapsedTicks, materialProperties)) {
                return true;
            }
        }
        return false;
    }

    private boolean applyClips(CompiledProfile profile, String material, List<ActiveClip> activeClips,
                               double[] values, String bone) {
        boolean touched = false;
        for (ActiveClip active : activeClips) {
            if (active == null || active.trigger() == null) {
                continue;
            }
            List<CompiledClip> clips = profile.clips().get(active.trigger());
            if (clips == null) {
                continue;
            }
            for (CompiledClip clip : clips) {
                touched |= clip.apply(material, active.elapsedTicks(), values, materialProperties, bone);
            }
        }
        return touched;
    }

    private CompiledProfile profile(String material) {
        if (profiles.isEmpty()) {
            return null;
        }
        CompiledProfile memoized = profileMemo.get(material);
        if (memoized == null) {
            memoized = NO_PROFILE;
            for (CompiledProfile profile : profiles) {
                if (profile.matches(material)) {
                    memoized = profile;
                    break;
                }
            }
            profileMemo.put(material, memoized);
        }
        return memoized == NO_PROFILE ? null : memoized;
    }

    private static Map<String, MaterialMap> compileMaterialProperties(
        Map<String, Map<String, MaterialProperties>> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, MaterialMap> compiled = new LinkedHashMap<>(source.size());
        for (Map.Entry<String, Map<String, MaterialProperties>> group : source.entrySet()) {
            String name = group.getKey() == null ? "" : group.getKey().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("animation material property map has no name");
            }
            if (compiled.containsKey(name)) {
                throw new IllegalArgumentException("animation material property map " + name + " is declared twice");
            }
            List<MaterialEntry> entries = new ArrayList<>();
            Map<String, MaterialProperties> values = group.getValue();
            if (values != null) {
                for (Map.Entry<String, MaterialProperties> entry : values.entrySet()) {
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

    private List<CompiledProfile> compileProfiles(List<Profile> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<CompiledProfile> compiled = new ArrayList<>(source.size());
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < source.size(); index++) {
            Profile profile = source.get(index);
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
            Map<Trigger, List<CompiledClip>> clips = compileClips(profile.clips());
            compiled.add(new CompiledProfile(
                id,
                profile.priority(),
                index,
                List.copyOf(materials),
                clips,
                clipDurations(clips)));
        }
        compiled.sort(Comparator.comparingInt(CompiledProfile::priority).reversed()
            .thenComparingInt(CompiledProfile::order));
        return List.copyOf(compiled);
    }

    private static Map<Trigger, Double> clipDurations(Map<Trigger, List<CompiledClip>> clips) {
        Map<Trigger, Double> durations = new HashMap<>();
        for (Map.Entry<Trigger, List<CompiledClip>> entry : clips.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            double duration = 0.0D;
            for (CompiledClip clip : entry.getValue()) {
                duration = Math.max(duration, clip.durationTicks());
            }
            durations.put(entry.getKey(), duration);
        }
        return Map.copyOf(durations);
    }

    private Map<Trigger, List<CompiledClip>> compileClips(List<Clip> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<Trigger, List<CompiledClip>> grouped = new LinkedHashMap<>();
        for (Clip clip : source) {
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
        Map<Trigger, List<CompiledClip>> immutable = new HashMap<>(grouped.size() * 2);
        for (Map.Entry<Trigger, List<CompiledClip>> entry : grouped.entrySet()) {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(immutable);
    }

    private List<CompiledTrack> compileTracks(Clip clip) {
        List<Track> source = clip.tracks();
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<CompiledTrack> tracks = new ArrayList<>(source.size());
        for (Track track : source) {
            if (track == null) {
                continue;
            }
            validateBlend(track.target(), track.blend());
            List<Keyframe> keyframes = track.keyframes();
            if (keyframes == null || keyframes.isEmpty()) {
                throw new IllegalArgumentException("animation track " + track.target() + " has no keyframes");
            }
            List<CompiledKeyframe> compiled = new ArrayList<>(keyframes.size());
            Set<Double> ticks = new HashSet<>();
            for (Keyframe keyframe : keyframes) {
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
                Easing.Curve easing = keyframe.easing() == null ? Easing.Curve.of(Easing.LINEAR) : keyframe.easing();
                compiled.add(new CompiledKeyframe(keyframe.tick(), keyframe.value(), materialMap, easing));
            }
            compiled.sort(Comparator.comparingDouble(CompiledKeyframe::tick));
            tracks.add(new CompiledTrack(track.bone(), track.target(), track.blend(), List.copyOf(compiled)));
        }
        return List.copyOf(tracks);
    }

    private void validateMaterialMap(Target target, String materialMap) {
        if (target != Target.GLOW && target != Target.LIGHT_LEVEL) {
            throw new IllegalArgumentException(
                "animation material property maps only support GLOW and LIGHT_LEVEL tracks");
        }
        if (!materialProperties.containsKey(materialMap)) {
            throw new IllegalArgumentException("animation material property map " + materialMap + " does not exist");
        }
    }

    private static void validateBlend(Target target, Blend blend) {
        if (target == null || blend == null) {
            throw new IllegalArgumentException("animation track target and blend are required");
        }
        boolean valid = switch (blend) {
            case REPLACE -> true;
            case ADD -> target.isTranslation() || target.isRotation();
            case MULTIPLY -> target.isScale();
        };
        if (!valid) {
            throw new IllegalArgumentException("animation blend " + blend + " is not valid for " + target);
        }
    }

    private static ClipSample sample(String profileId, double[] values) {
        return new ClipSample(
            profileId,
            values[Target.OFFSET_X.channel()],
            values[Target.OFFSET_Y.channel()],
            values[Target.OFFSET_Z.channel()],
            values[Target.ROTATION_X.channel()],
            values[Target.ROTATION_Y.channel()],
            values[Target.ROTATION_Z.channel()],
            clamp(values[Target.SCALE_X.channel()], 0.0D, 16.0D),
            clamp(values[Target.SCALE_Y.channel()], 0.0D, 16.0D),
            clamp(values[Target.SCALE_Z.channel()], 0.0D, 16.0D),
            Math.round(values[Target.GLOW.channel()]) & 0xFFFFFFFFL,
            values[Target.VISIBLE.channel()] >= 0.5D,
            values[Target.PHYSICS.channel()] >= 0.5D,
            (int) Math.round(clamp(values[Target.LIGHT_LEVEL.channel()], 0.0D, 15.0D)),
            clamp(values[Target.OPACITY.channel()], 0.0D, 1.0D),
            values[Target.BRIGHTNESS.channel()]);
    }

    private static double[] neutralValues() {
        double[] values = new double[TARGET_COUNT];
        values[Target.SCALE_X.channel()] = 1.0D;
        values[Target.SCALE_Y.channel()] = 1.0D;
        values[Target.SCALE_Z.channel()] = 1.0D;
        values[Target.VISIBLE.channel()] = 1.0D;
        values[Target.PHYSICS.channel()] = 1.0D;
        values[Target.OPACITY.channel()] = ClipSample.NEUTRAL_OPACITY;
        values[Target.BRIGHTNESS.channel()] = ClipSample.UNSET_BRIGHTNESS;
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

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record ActiveClip(Trigger trigger, double elapsedTicks) {
    }

    private record CompiledProfile(
        String id,
        int priority,
        int order,
        List<Pattern> materials,
        Map<Trigger, List<CompiledClip>> clips,
        Map<Trigger, Double> durations
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
        boolean apply(String material, double elapsedTicks, double[] values, Map<String, MaterialMap> properties,
                      String bone) {
            double tick = Math.max(0.0D, elapsedTicks);
            if (loop && durationTicks > 0.0D) {
                tick %= durationTicks;
            } else {
                tick = Math.min(durationTicks, tick);
            }
            boolean touched = false;
            for (CompiledTrack track : tracks) {
                if (bone != null && !track.appliesTo(bone)) {
                    continue;
                }
                track.apply(material, tick, values, properties);
                touched = true;
            }
            return touched;
        }

        boolean requiresContinuousUpdates(String material, double elapsedTicks, Map<String, MaterialMap> properties) {
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

    private record CompiledTrack(String bone, Target target, Blend blend, List<CompiledKeyframe> keyframes) {
        boolean appliesTo(String boneId) {
            return bone == null || bone.equals(Track.EVERY_BONE) || bone.equals(boneId);
        }

        void apply(String material, double tick, double[] values, Map<String, MaterialMap> properties) {
            double value = value(material, tick, properties);
            int index = target.channel();
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
                    double eased = next.easing().apply(progress);
                    double from = previous.value(material, target, properties);
                    double to = next.value(material, target, properties);
                    return from + (to - from) * eased;
                }
                previous = next;
            }
            return keyframes.getLast().value(material, target, properties);
        }

        private boolean requiresContinuousUpdates(String material, double tick, Map<String, MaterialMap> properties) {
            if (keyframes.size() < 2 || tick < keyframes.getFirst().tick() || tick >= keyframes.getLast().tick()) {
                return false;
            }
            CompiledKeyframe previous = keyframes.getFirst();
            for (int index = 1; index < keyframes.size(); index++) {
                CompiledKeyframe next = keyframes.get(index);
                if (tick < next.tick()) {
                    if (next.easing().easing() == Easing.HOLD) {
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

    private record CompiledKeyframe(double tick, double value, String materialMap, Easing.Curve easing) {
        double value(String material, Target target, Map<String, MaterialMap> properties) {
            if (materialMap.isEmpty()) {
                return value;
            }
            MaterialMap map = properties.get(materialMap);
            return map == null ? value : map.value(material, target, value);
        }
    }

    private record MaterialMap(List<MaterialEntry> entries) {
        double value(String material, Target target, double fallback) {
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

    private record MaterialEntry(Pattern pattern, MaterialProperties properties) {
    }
}
