package art.arcane.gloss.rig;

import com.github.retrooper.packetevents.util.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoneTransformTest {
    private static final float EPSILON = 1.0E-4F;

    private static void assertVector(float x, float y, float z, Vector3f actual) {
        assertEquals(x, actual.getX(), EPSILON, "x");
        assertEquals(y, actual.getY(), EPSILON, "y");
        assertEquals(z, actual.getZ(), EPSILON, "z");
    }

    @Test
    void childTranslatedOnXUnderAParentRotatedNinetyAroundYLandsOnMinusZ() {
        Transform parent = new Transform(new Vector3f(0.0F, 0.0F, 0.0F), Quaternions.fromEulerDegrees(0.0F, 90.0F, 0.0F),
            new Vector3f(1.0F, 1.0F, 1.0F));
        Transform child = new Transform(new Vector3f(1.0F, 0.0F, 0.0F), Quaternions.identity(), new Vector3f(1.0F, 1.0F, 1.0F));

        Transform world = parent.compose(child);
        assertVector(0.0F, 0.0F, -1.0F, world.translation());
        assertEquals(parent.rotation().getY(), world.rotation().getY(), EPSILON);
        assertEquals(parent.rotation().getW(), world.rotation().getW(), EPSILON);
    }

    @Test
    void parentTranslationOffsetsTheChildAfterRotation() {
        Transform parent = new Transform(new Vector3f(10.0F, 5.0F, -3.0F), Quaternions.fromEulerDegrees(0.0F, 90.0F, 0.0F),
            new Vector3f(1.0F, 1.0F, 1.0F));
        Transform child = new Transform(new Vector3f(1.0F, 2.0F, 0.0F), Quaternions.identity(), new Vector3f(1.0F, 1.0F, 1.0F));

        assertVector(10.0F, 7.0F, -4.0F, parent.compose(child).translation());
    }

    @Test
    void scaleComposesMultiplicativelyAndScalesTheChildOffset() {
        Transform parent = new Transform(new Vector3f(0.0F, 0.0F, 0.0F), Quaternions.identity(), new Vector3f(2.0F, 2.0F, 2.0F));
        Transform child = new Transform(new Vector3f(1.0F, 0.0F, 0.0F), Quaternions.identity(), new Vector3f(0.5F, 1.0F, 1.0F));

        Transform world = parent.compose(child);
        assertVector(2.0F, 0.0F, 0.0F, world.translation());
        assertVector(1.0F, 2.0F, 2.0F, world.scale());
    }

    @Test
    void identityComposesToIdentity() {
        Transform world = Transform.identity().compose(Transform.identity());
        assertVector(0.0F, 0.0F, 0.0F, world.translation());
        assertVector(1.0F, 1.0F, 1.0F, world.scale());
        assertEquals(0.0F, world.rotation().getX(), EPSILON);
        assertEquals(0.0F, world.rotation().getY(), EPSILON);
        assertEquals(0.0F, world.rotation().getZ(), EPSILON);
        assertEquals(1.0F, world.rotation().getW(), EPSILON);
    }
}
