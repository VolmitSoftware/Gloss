package art.arcane.gloss.motion;

import art.arcane.gloss.animation.clip.Blend;
import art.arcane.gloss.animation.clip.ClipPlan;
import art.arcane.gloss.animation.clip.Easing;
import art.arcane.gloss.animation.clip.LoopMode;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record MotionDoc(
    int schemaVersion,
    long revision,
    Double durationTicks,
    String loop,
    Integer fps,
    List<MotionTrack> tracks
) {
    public static final String KIND = "motion";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int DEFAULT_FPS = 20;
    public static final int MIN_FPS = 1;
    public static final int MAX_FPS = 120;
    public static final int MAX_TRACKS = 256;
    public static final double MAX_DURATION_TICKS = 1_000_000.0D;
    public static final String DEFAULT_BONE = "root";
    public static final String DEFAULT_LOOP = "loop";

    public MotionDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        tracks = copyTracks(tracks);
        durationTicks = requireDuration(durationTicks, tracks);
        loop = requireLoop(loop);
        fps = fps == null ? DEFAULT_FPS : Math.clamp(fps, MIN_FPS, MAX_FPS);
        ClipPlan.validate(MotionService.compile(durationTicks, loopMode(loop), tracks));
    }

    public static MotionDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, MotionDoc.class);
    }

    public MotionDoc withRevision(long revision) {
        return new MotionDoc(schemaVersion, revision, durationTicks, loop, fps, tracks);
    }

    public LoopMode loopMode() {
        return loopMode(loop);
    }

    private static LoopMode loopMode(String loop) {
        return LoopMode.valueOf(loop.toUpperCase(Locale.ROOT));
    }

    private static String requireLoop(String loop) {
        String normalized = loop == null || loop.isBlank() ? DEFAULT_LOOP : loop.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "once", "loop", "pingpong" -> normalized;
            default -> throw new IllegalArgumentException("motion loop must be once, loop or pingpong, got '" + loop + "'");
        };
    }

    private static double requireDuration(Double durationTicks, List<MotionTrack> tracks) {
        if (durationTicks == null) {
            double longest = 0.0D;
            for (MotionTrack track : tracks) {
                for (MotionKeyframe keyframe : track.keyframes()) {
                    longest = Math.max(longest, keyframe.tick());
                }
            }
            return longest;
        }
        if (!Double.isFinite(durationTicks) || durationTicks < 0.0D || durationTicks > MAX_DURATION_TICKS) {
            throw new IllegalArgumentException("motion durationTicks must be between 0 and " + MAX_DURATION_TICKS);
        }
        return durationTicks;
    }

    private static List<MotionTrack> copyTracks(List<MotionTrack> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            throw new IllegalArgumentException("motion needs at least one track");
        }
        if (tracks.size() > MAX_TRACKS) {
            throw new IllegalArgumentException("motion may declare at most " + MAX_TRACKS + " tracks");
        }
        List<MotionTrack> copied = new ArrayList<>(tracks.size());
        for (MotionTrack track : tracks) {
            copied.add(Objects.requireNonNull(track, "motion tracks must not contain null entries"));
        }
        return List.copyOf(copied);
    }

    public record MotionTrack(String bone, String channel, String blend, List<MotionKeyframe> keyframes) {
        public MotionTrack {
            bone = bone == null || bone.isBlank() ? DEFAULT_BONE : bone.trim();
            MotionChannel kind = MotionChannel.parse(channel);
            channel = kind.channelName();
            blend = blend == null || blend.isBlank()
                ? kind.defaultBlend().name().toLowerCase(Locale.ROOT)
                : Blend.parse(blend).name().toLowerCase(Locale.ROOT);
            if (keyframes == null || keyframes.isEmpty()) {
                throw new IllegalArgumentException("motion track " + channel + " on bone " + bone + " has no keyframes");
            }
            List<MotionKeyframe> copied = new ArrayList<>(keyframes.size());
            for (MotionKeyframe keyframe : keyframes) {
                Objects.requireNonNull(keyframe, "motion keyframes must not contain null entries");
                kind.value(keyframe.value());
                copied.add(keyframe);
            }
            keyframes = List.copyOf(copied);
        }

        public MotionChannel channelKind() {
            return MotionChannel.parse(channel);
        }

        public Blend blendKind() {
            return Blend.parse(blend);
        }
    }

    public record MotionKeyframe(Double tick, Object value, String easing) {
        public MotionKeyframe {
            if (tick == null || !Double.isFinite(tick) || tick < 0.0D) {
                throw new IllegalArgumentException("motion keyframe tick must be a finite number of at least zero");
            }
            if (value == null) {
                throw new IllegalArgumentException("motion keyframe at tick " + tick + " has no value");
            }
            if (easing != null && !easing.isBlank()) {
                Easing.parse(easing);
            }
        }

        public Easing.Curve curve() {
            return easing == null || easing.isBlank() ? Easing.Curve.of(Easing.LINEAR) : Easing.parse(easing);
        }

        public double numericValue() {
            return value instanceof Number number ? number.doubleValue()
                : value instanceof Boolean flag ? (flag ? 1.0D : 0.0D)
                : MotionChannel.GLOW.value(value);
        }
    }
}
