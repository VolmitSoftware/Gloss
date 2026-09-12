package art.arcane.gloss.animation.clip;

import java.util.List;

public record Track(String bone, Target target, Blend blend, List<Keyframe> keyframes) {
    public static final String EVERY_BONE = "*";

    public boolean appliesTo(String boneId) {
        return bone == null || bone.equals(EVERY_BONE) || bone.equals(boneId);
    }
}
