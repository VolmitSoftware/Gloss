package art.arcane.gloss.drop;

import org.joml.Quaternionf;

final class RealDropAnimationState {
    private final Quaternionf rotation;

    private RealDropModel.Angles angularVelocity;
    private Phase phase;
    private int phaseTicks;
    private int stableTicks;
    private double impactSpeed;
    private boolean poseDirty;

    RealDropAnimationState(Quaternionf rotation, RealDropModel.Angles angularVelocity, boolean supported) {
        this.rotation = new Quaternionf(rotation);
        this.angularVelocity = angularVelocity;
        this.phase = supported ? Phase.SETTLING : Phase.AIRBORNE;
        this.poseDirty = true;
    }

    Quaternionf rotation() {
        return rotation;
    }

    RealDropModel.Angles angularVelocity() {
        return angularVelocity;
    }

    void angularVelocity(RealDropModel.Angles angularVelocity) {
        this.angularVelocity = angularVelocity;
    }

    Phase phase() {
        return phase;
    }

    int phaseTicks() {
        return phaseTicks;
    }

    int stableTicks() {
        return stableTicks;
    }

    void stableTicks(int stableTicks) {
        this.stableTicks = Math.max(0, stableTicks);
    }

    double impactSpeed() {
        return impactSpeed;
    }

    void impactSpeed(double impactSpeed) {
        this.impactSpeed = Math.max(0.0D, impactSpeed);
    }

    boolean consumePoseDirty() {
        boolean dirty = poseDirty;
        poseDirty = false;
        return dirty;
    }

    void markPoseDirty() {
        poseDirty = true;
    }

    void transition(Phase next, int deltaTicks) {
        if (phase == next) {
            phaseTicks += deltaTicks;
            return;
        }
        phase = next;
        phaseTicks = 0;
        poseDirty = true;
    }

    enum Phase {
        AIRBORNE,
        REBOUNDING,
        ROLLING,
        SETTLING,
        SETTLED,
        SUBMERGED
    }
}
