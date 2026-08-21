package art.arcane.gloss.drop;

import art.arcane.gloss.GlossConfig;
import org.joml.Quaternionf;

import java.util.UUID;

final class RealDropAnimationEngine {
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0D);
    private static final double SETTLED_HORIZONTAL_VELOCITY_SQUARED = 0.000001D;

    private RealDropAnimationEngine() {
    }

    static RealDropAnimationFrame advance(UUID itemId, RealDropModel.ModelKind kind,
                                          RealDropAnimationState state, RealDropAnimationInput input,
                                          GlossConfig.RealDrops config) {
        if (input.bounced()) {
            state.impactSpeed(input.impactSpeed());
        }
        if (input.submerged()) {
            advanceAirborne(state, input, config, config.motion().submergedSpinMultiplier());
            state.stableTicks(0);
            state.transition(RealDropAnimationState.Phase.SUBMERGED, input.deltaTicks());
            return frame(state, config, false);
        }
        if (!input.supported()) {
            advanceAirborne(state, input, config, 1.0F);
            state.stableTicks(0);
            state.transition(input.bounced() ? RealDropAnimationState.Phase.REBOUNDING
                : RealDropAnimationState.Phase.AIRBORNE, input.deltaTicks());
            return frame(state, config, false);
        }

        boolean aligned = advanceSupported(itemId, kind, state, input, config);
        boolean moving = input.horizontalSpeedSquared() > SETTLED_HORIZONTAL_VELOCITY_SQUARED;
        if (moving) {
            state.stableTicks(0);
            state.transition(RealDropAnimationState.Phase.ROLLING, input.deltaTicks());
            return frame(state, config, false);
        }
        if (!aligned) {
            state.stableTicks(0);
            state.transition(RealDropAnimationState.Phase.SETTLING, input.deltaTicks());
            return frame(state, config, false);
        }

        state.stableTicks(state.stableTicks() + input.deltaTicks());
        int requiredStableTicks = Math.max(input.deltaTicks(), config.landing().settleDelayTicks());
        boolean settled = state.stableTicks() >= requiredStableTicks;
        state.transition(settled ? RealDropAnimationState.Phase.SETTLED
            : RealDropAnimationState.Phase.SETTLING, input.deltaTicks());
        return frame(state, config, settled);
    }

    private static void advanceAirborne(RealDropAnimationState state, RealDropAnimationInput input,
                                        GlossConfig.RealDrops config, float mediumMultiplier) {
        if (!config.motion().tumble()) {
            return;
        }
        float seconds = input.deltaTicks() / 20.0F;
        float momentumMultiplier = 1.0F + (float) Math.min(
            4.0D, input.speed() * config.motion().velocityInfluence());
        RealDropModel.Angles angular = state.angularVelocity();
        state.rotation().rotateXYZ(
            angular.x() * seconds * DEG_TO_RAD * momentumMultiplier * mediumMultiplier,
            angular.y() * seconds * DEG_TO_RAD * momentumMultiplier * mediumMultiplier,
            angular.z() * seconds * DEG_TO_RAD * momentumMultiplier * mediumMultiplier);
        state.markPoseDirty();
    }

    private static boolean advanceSupported(UUID itemId, RealDropModel.ModelKind kind,
                                            RealDropAnimationState state, RealDropAnimationInput input,
                                            GlossConfig.RealDrops config) {
        if (kind != RealDropModel.ModelKind.FLAT && "NATURAL".equals(config.landing().mode())) {
            float scale = RealDropModel.scale(kind, config.scale());
            RealDropModel.BlockRoll roll = RealDropModel.groundedBlockRotation(
                state.rotation(), input.deltaX(), input.deltaZ(),
                Math.sqrt(input.horizontalSpeedSquared()), scale,
                config.motion().groundRollMultiplier(), config.landing().faceAttraction(),
                config.landing().movingFaceAttraction(), alignmentRadians(config));
            if (!sameRotation(state.rotation(), roll.rotation())) {
                state.rotation().set(roll.rotation());
                state.markPoseDirty();
            }
            return roll.aligned();
        }

        Quaternionf target = kind == RealDropModel.ModelKind.FLAT
            ? RealDropModel.broadFaceAlignedRotation(state.rotation())
            : RealDropModel.landingRotation(itemId, kind, config.landing());
        float difference = RealDropModel.rotationDifference(state.rotation(), target);
        if (difference <= alignmentRadians(config)) {
            if (!sameRotation(state.rotation(), target)) {
                state.rotation().set(target);
                state.markPoseDirty();
            }
            return true;
        }
        double speedReference = Math.max(0.02D, RealDropModel.scale(kind, config.scale()) * 0.25D);
        float motionRatio = (float) Math.min(1.0D,
            Math.sqrt(input.horizontalSpeedSquared()) / speedReference);
        float attraction = config.landing().faceAttraction()
            + (config.landing().movingFaceAttraction() - config.landing().faceAttraction()) * motionRatio;
        state.rotation().slerp(target, attraction);
        state.markPoseDirty();
        return false;
    }

    private static RealDropAnimationFrame frame(RealDropAnimationState state,
                                                GlossConfig.RealDrops config, boolean settled) {
        int pollDelay = settled
            ? config.limits().settledPollIntervalTicks()
            : config.limits().updateIntervalTicks();
        int interpolation = settled
            ? config.landing().transitionTicks()
            : config.limits().updateIntervalTicks();
        return new RealDropAnimationFrame(
            state.rotation(), state.phase(), state.phaseTicks(), state.impactSpeed(), settled,
            state.consumePoseDirty(), pollDelay, interpolation);
    }

    private static boolean sameRotation(Quaternionf first, Quaternionf second) {
        return Math.abs(first.dot(second)) >= 0.999999F;
    }

    private static float alignmentRadians(GlossConfig.RealDrops config) {
        return config.landing().alignmentDegrees() * DEG_TO_RAD;
    }
}
