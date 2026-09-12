package art.arcane.gloss.beam;

import com.github.retrooper.packetevents.util.Vector3f;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class BeamMathTest {
    private static final double EPSILON = 1.0E-5D;

    @Test
    void aBeamAlongPositiveZKeepsTheIdentityRotation() {
        BeamMath.Transform transform = BeamMath.beamBetween(
            new Vector(0, 0, 0), new Vector(0, 0, 10), 0.25D);

        Assertions.assertEquals(new Vector(0, 0, 5), transform.midpoint());
        assertScale(transform.scale(), 0.25F, 0.25F, 10.0F);
        assertRotates(transform, new Vector(0, 0, 1), new Vector(0, 0, 1));
    }

    @Test
    void aBeamAlongPositiveXIsAQuarterTurnAboutY() {
        BeamMath.Transform transform = BeamMath.beamBetween(
            new Vector(0, 0, 0), new Vector(10, 0, 0), 0.5D);

        Assertions.assertEquals(new Vector(5, 0, 0), transform.midpoint());
        assertScale(transform.scale(), 0.5F, 0.5F, 10.0F);
        assertRotates(transform, new Vector(0, 0, 1), new Vector(1, 0, 0));
    }

    @Test
    void aBeamAlongNegativeXTurnsTheOtherWay() {
        BeamMath.Transform transform = BeamMath.beamBetween(
            new Vector(0, 0, 0), new Vector(-10, 0, 0), 0.5D);

        assertRotates(transform, new Vector(0, 0, 1), new Vector(-1, 0, 0));
    }

    @Test
    void aVerticalBeamPointsItsLengthUp() {
        BeamMath.Transform transform = BeamMath.beamBetween(
            new Vector(0, 60, 0), new Vector(0, 80, 0), 0.25D);

        Assertions.assertEquals(new Vector(0, 70, 0), transform.midpoint());
        assertScale(transform.scale(), 0.25F, 0.25F, 20.0F);
        assertRotates(transform, new Vector(0, 0, 1), new Vector(0, 1, 0));
    }

    @Test
    void aZeroLengthBeamKeepsAZeroLengthScaleAndTheIdentityRotation() {
        BeamMath.Transform transform = BeamMath.beamBetween(
            new Vector(1, 2, 3), new Vector(1, 2, 3), 0.25D);

        Assertions.assertEquals(0.0F, transform.scale().z, EPSILON);
        assertRotates(transform, new Vector(0, 0, 1), new Vector(0, 0, 1));
    }

    @Test
    void aTrailWalksFromTheViewerToTheTargetAtTheSpacing() {
        List<Vector> points = BeamMath.trailPoints(
            new Vector(0, 64, 0), new Vector(0, 64, 10), 2.0D, 48);

        Assertions.assertEquals(6, points.size());
        Assertions.assertEquals(0.0D, points.getFirst().getZ(), EPSILON);
        Assertions.assertEquals(10.0D, points.getLast().getZ(), EPSILON);
    }

    @Test
    void aTrailNeverExceedsItsMaximumPointCount() {
        List<Vector> points = BeamMath.trailPoints(
            new Vector(0, 64, 0), new Vector(0, 64, 400), 1.0D, 48);

        Assertions.assertEquals(48, points.size());
    }

    @Test
    void aTrailBetweenTwoIdenticalPointsIsASinglePoint() {
        Assertions.assertEquals(1, BeamMath.trailPoints(
            new Vector(1, 2, 3), new Vector(1, 2, 3), 2.0D, 48).size());
    }

    @Test
    void aTrailIsResampledOnlyAfterTheViewerHasReallyMoved() {
        Vector sampledAt = new Vector(0, 64, 0);

        Assertions.assertFalse(BeamMath.shouldResample(sampledAt, new Vector(1, 64, 0)));
        Assertions.assertTrue(BeamMath.shouldResample(sampledAt, new Vector(3, 64, 0)));
        Assertions.assertTrue(BeamMath.shouldResample(null, new Vector(0, 64, 0)));
    }

    private static void assertScale(Vector3f scale, float x, float y, float z) {
        Assertions.assertEquals(x, scale.x, EPSILON);
        Assertions.assertEquals(y, scale.y, EPSILON);
        Assertions.assertEquals(z, scale.z, EPSILON);
    }

    private static void assertRotates(BeamMath.Transform transform, Vector input, Vector expected) {
        Vector rotated = BeamMath.rotate(transform.rotation(), input);
        Assertions.assertEquals(expected.getX(), rotated.getX(), EPSILON);
        Assertions.assertEquals(expected.getY(), rotated.getY(), EPSILON);
        Assertions.assertEquals(expected.getZ(), rotated.getZ(), EPSILON);
    }
}
