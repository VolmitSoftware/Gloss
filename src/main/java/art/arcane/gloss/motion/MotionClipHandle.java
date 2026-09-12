package art.arcane.gloss.motion;

import art.arcane.gloss.animation.clip.ClipPlan;
import art.arcane.gloss.animation.clip.ClipSample;
import art.arcane.gloss.animation.clip.LoopMode;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class MotionClipHandle {
    private static final double MILLIS_PER_TICK = 50.0D;

    private final Function<String, Optional<MotionService.Compiled>> resolver;
    private volatile Playing playing;

    public MotionClipHandle(Function<String, Optional<MotionService.Compiled>> resolver) {
        this.resolver = resolver;
    }

    public MotionClipHandle(MotionService service) {
        this(service::compiled);
    }

    public boolean play(String motionId, long nowMs) {
        return play(motionId, nowMs, null);
    }

    public boolean play(String motionId, long nowMs, LoopMode loopOverride) {
        Optional<MotionService.Compiled> compiled = resolver.apply(motionId);
        if (compiled.isEmpty()) {
            return false;
        }
        playing = new Playing(motionId, compiled.get(), nowMs,
            loopOverride == null ? compiled.get().doc().loopMode() : loopOverride);
        return true;
    }

    public void stop() {
        playing = null;
    }

    public boolean playing() {
        return playing != null;
    }

    public String motionId() {
        Playing current = playing;
        return current == null ? null : current.motionId();
    }

    public long startedAtMs() {
        Playing current = playing;
        return current == null ? Long.MIN_VALUE : current.startedAtMs();
    }

    public int fps() {
        Playing current = playing;
        return current == null ? 0 : current.compiled().doc().fps();
    }

    public ClipPlan plan() {
        Playing current = playing;
        return current == null ? null : current.compiled().plan();
    }

    public double elapsedTicks(long nowMs) {
        Playing current = playing;
        return current == null ? 0.0D : current.elapsedTicks(nowMs);
    }

    /**
     * Whether this clip still has new frames to send. A {@code once} clip that has run past its
     * duration keeps its final pose but stops streaming, so a rig frozen on its last frame does
     * not keep spending the shared transform budget that every moving rig shares.
     */
    public boolean streaming(long nowMs) {
        Playing current = playing;
        return current != null && !current.finished(nowMs);
    }

    public boolean finished(long nowMs) {
        Playing current = playing;
        return current != null && current.finished(nowMs);
    }

    public List<ClipPlan.ActiveClip> active(long nowMs) {
        Playing current = playing;
        return current == null ? List.of() : List.of(new ClipPlan.ActiveClip(MotionService.PLAY, current.elapsedTicks(nowMs)));
    }

    public Map<String, ClipSample> sampleBones(Collection<String> bones, long nowMs) {
        Playing current = playing;
        if (current == null) {
            Map<String, ClipSample> neutral = new HashMap<>(Math.max(1, bones.size()) * 2);
            for (String bone : bones) {
                neutral.put(bone, ClipSample.NEUTRAL);
            }
            return neutral;
        }
        return current.compiled().plan().sampleBones(bones,
            List.of(new ClipPlan.ActiveClip(MotionService.PLAY, current.elapsedTicks(nowMs))));
    }

    private record Playing(String motionId, MotionService.Compiled compiled, long startedAtMs, LoopMode mode) {
        double elapsedTicks(long nowMs) {
            double raw = Math.max(0L, nowMs - startedAtMs) / MILLIS_PER_TICK;
            double duration = compiled.doc().durationTicks();
            if (duration <= 0.0D) {
                return 0.0D;
            }
            return switch (mode) {
                case ONCE -> Math.min(duration, raw);
                case LOOP -> raw % duration;
                case PINGPONG -> {
                    double cycle = raw % (duration * 2.0D);
                    yield cycle > duration ? duration * 2.0D - cycle : cycle;
                }
            };
        }

        boolean finished(long nowMs) {
            if (mode != LoopMode.ONCE) {
                return false;
            }
            double raw = Math.max(0L, nowMs - startedAtMs) / MILLIS_PER_TICK;
            return raw >= compiled.doc().durationTicks();
        }
    }
}
