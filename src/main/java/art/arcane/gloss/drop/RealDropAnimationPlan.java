package art.arcane.gloss.drop;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.animation.clip.ClipPlan;
import art.arcane.gloss.animation.clip.ClipSample;

import java.util.ArrayList;
import java.util.List;

final class RealDropAnimationPlan {
    private final ClipPlan plan;

    private RealDropAnimationPlan(GlossConfig.RealDrops.RealDropAnimation animation) {
        plan = ClipPlan.compile(animation == null ? null : animation.toClipSet());
    }

    static RealDropAnimationPlan compile(GlossConfig.RealDrops.RealDropAnimation animation) {
        return new RealDropAnimationPlan(animation);
    }

    static void validate(GlossConfig.RealDrops.RealDropAnimation animation) {
        new RealDropAnimationPlan(animation);
    }

    boolean enabled() {
        return plan.enabled();
    }

    AnimationSample sample(String material, GlossConfig.RealDrops.AnimationTrigger trigger, double elapsedTicks) {
        return AnimationSample.of(plan.sample(material, trigger == null ? null : trigger.toClip(), elapsedTicks));
    }

    AnimationSample sample(String material, List<ActiveClip> activeClips) {
        if (activeClips == null || activeClips.isEmpty()) {
            return AnimationSample.of(plan.sample(material, List.of()));
        }
        List<ClipPlan.ActiveClip> converted = new ArrayList<>(activeClips.size());
        for (ActiveClip active : activeClips) {
            converted.add(active == null ? null
                : new ClipPlan.ActiveClip(active.trigger() == null ? null : active.trigger().toClip(),
                    active.elapsedTicks()));
        }
        return AnimationSample.of(plan.sample(material, converted));
    }

    String profileId(String material) {
        return plan.profileId(material);
    }

    double clipDurationTicks(String material, GlossConfig.RealDrops.AnimationTrigger trigger) {
        return plan.clipDurationTicks(material, trigger == null ? null : trigger.toClip());
    }

    boolean requiresContinuousUpdates(
        String material,
        GlossConfig.RealDrops.AnimationTrigger trigger,
        double elapsedTicks
    ) {
        return plan.requiresContinuousUpdates(material, trigger == null ? null : trigger.toClip(), elapsedTicks);
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
        private static final AnimationSample NEUTRAL = new AnimationSample(
            "", 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D, 0L, true, true, 0);

        static AnimationSample neutral(String profileId) {
            if (profileId.isEmpty()) {
                return NEUTRAL;
            }
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

        static AnimationSample of(ClipSample sample) {
            if (sample.isNeutral()) {
                return NEUTRAL;
            }
            return new AnimationSample(
                sample.profileId(),
                sample.offsetX(),
                sample.offsetY(),
                sample.offsetZ(),
                sample.rotationX(),
                sample.rotationY(),
                sample.rotationZ(),
                sample.scaleX(),
                sample.scaleY(),
                sample.scaleZ(),
                sample.glowArgb(),
                sample.visible(),
                sample.physics(),
                sample.lightLevel());
        }
    }
}
