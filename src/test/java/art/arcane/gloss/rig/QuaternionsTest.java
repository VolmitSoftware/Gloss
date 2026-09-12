package art.arcane.gloss.rig;

import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuaternionsTest {
    private static final float EPSILON = 1.0E-4F;

    private static void assertSame(Quaternionf expected, Quaternion4f actual) {
        assertEquals(expected.x(), actual.getX(), EPSILON, "x");
        assertEquals(expected.y(), actual.getY(), EPSILON, "y");
        assertEquals(expected.z(), actual.getZ(), EPSILON, "z");
        assertEquals(expected.w(), actual.getW(), EPSILON, "w");
    }

    @Test
    void eulerOrderMatchesTheDropPoseMathForNinetyAroundX() {
        assertSame(new Quaternionf().rotateX((float) Math.toRadians(90.0D)), Quaternions.fromEulerDegrees(90.0F, 0.0F, 0.0F));
    }

    @Test
    void eulerOrderIsXThenYThenZ() {
        Quaternionf expected = new Quaternionf()
            .rotateX((float) Math.toRadians(30.0D))
            .rotateY((float) Math.toRadians(45.0D))
            .rotateZ((float) Math.toRadians(60.0D));
        assertSame(expected, Quaternions.fromEulerDegrees(30.0F, 45.0F, 60.0F));
    }

    @Test
    void multiplyMatchesJoml() {
        Quaternionf a = new Quaternionf().rotateY(0.7F);
        Quaternionf b = new Quaternionf().rotateX(-0.4F).rotateZ(1.1F);
        Quaternion4f left = new Quaternion4f(a.x(), a.y(), a.z(), a.w());
        Quaternion4f right = new Quaternion4f(b.x(), b.y(), b.z(), b.w());
        assertSame(new Quaternionf(a).mul(b), Quaternions.multiply(left, right));
    }

    @Test
    void rotatingPlusXNinetyAroundYLandsOnMinusZ() {
        Vector3f rotated = Quaternions.rotate(Quaternions.fromEulerDegrees(0.0F, 90.0F, 0.0F), new Vector3f(1.0F, 0.0F, 0.0F));
        assertEquals(0.0F, rotated.getX(), EPSILON);
        assertEquals(0.0F, rotated.getY(), EPSILON);
        assertEquals(-1.0F, rotated.getZ(), EPSILON);
    }

    @Test
    void lookAtTowardPlusZIsIdentityAndTowardPlusXIsNinetyAroundY() {
        Vector3f origin = new Vector3f(0.0F, 0.0F, 0.0F);
        Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F);
        assertSame(new Quaternionf(), Quaternions.lookAt(origin, new Vector3f(0.0F, 0.0F, 4.0F), up));
        assertSame(new Quaternionf().rotateY((float) Math.toRadians(90.0D)),
            Quaternions.lookAt(origin, new Vector3f(2.0F, 0.0F, 0.0F), up));
    }

    @Test
    void normalizeRestoresUnitLength() {
        Quaternion4f scaled = new Quaternion4f(0.0F, 2.0F, 0.0F, 0.0F);
        Quaternion4f unit = Quaternions.normalize(scaled);
        assertEquals(1.0F, unit.getY(), EPSILON);
        assertEquals(0.0F, unit.getW(), EPSILON);
        Quaternion4f degenerate = Quaternions.normalize(new Quaternion4f(0.0F, 0.0F, 0.0F, 0.0F));
        assertEquals(1.0F, degenerate.getW(), EPSILON);
    }
}
