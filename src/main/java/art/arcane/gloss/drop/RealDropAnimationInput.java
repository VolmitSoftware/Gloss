package art.arcane.gloss.drop;

record RealDropAnimationInput(
    int deltaTicks,
    boolean supported,
    boolean submerged,
    boolean inLava,
    boolean bounced,
    double deltaX,
    double deltaZ,
    double velocityX,
    double velocityY,
    double velocityZ,
    double impactSpeed
) {
    RealDropAnimationInput {
        deltaTicks = Math.max(1, deltaTicks);
        impactSpeed = Math.max(0.0D, impactSpeed);
    }

    double horizontalSpeedSquared() {
        return velocityX * velocityX + velocityZ * velocityZ;
    }

    double speed() {
        return Math.sqrt(horizontalSpeedSquared() + velocityY * velocityY);
    }
}
