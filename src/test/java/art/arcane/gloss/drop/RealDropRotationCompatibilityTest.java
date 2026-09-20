package art.arcane.gloss.drop;

import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealDropRotationCompatibilityTest {
    @Test
    void equivalentQuaternionSignsKeepTheSamePose() {
        Quaternionf rotation = new Quaternionf().rotationXYZ(0.4F, -0.6F, 0.2F);
        Quaternionf opposite = new Quaternionf(-rotation.x(), -rotation.y(), -rotation.z(), -rotation.w());
        assertTrue(RealDropAnimationEngine.sameRotation(rotation, new Quaternionf(rotation)));
        assertTrue(RealDropAnimationEngine.sameRotation(rotation, opposite));
    }

    @Test
    void rotationToleranceSeparatesRestingAndMovingPoses() {
        Quaternionf identity = new Quaternionf();
        assertTrue(RealDropAnimationEngine.sameRotation(identity, new Quaternionf().rotationY(0.001F)));
        assertFalse(RealDropAnimationEngine.sameRotation(identity, new Quaternionf().rotationY(0.004F)));
        assertFalse(RealDropAnimationEngine.sameRotation(identity, new Quaternionf().rotationY(0.5F)));
    }
}
