package art.arcane.gloss.drop;

import org.joml.Quaternionf;

record RealDropAnimationFrame(
    Quaternionf rotation,
    RealDropAnimationState.Phase phase,
    int stateTicks,
    double impactSpeed,
    boolean settled,
    boolean poseChanged,
    int pollDelayTicks,
    int interpolationTicks
) {
    RealDropAnimationFrame {
        rotation = new Quaternionf(rotation);
    }
}
