package art.arcane.gloss.camera;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class SplineTest {
    private static final double EPSILON = 1.0E-6D;

    @Test
    void theMidpointOfAStraightTwoNodePathIsHalfway() {
        Spline spline = new Spline(List.of(
            node(0, 64, 0, 0, 0, 40), node(0, 64, 40, 0, 0, 0)));

        Spline.Pose pose = spline.at(20);

        Assertions.assertEquals(20.0D, pose.z(), EPSILON);
        Assertions.assertEquals(64.0D, pose.y(), EPSILON);
    }

    @Test
    void aPathStartsOnItsFirstNodeAndEndsOnItsLast() {
        Spline spline = new Spline(List.of(
            node(1, 64, 2, 90, 10, 40), node(3, 70, 4, 180, -10, 0)));

        Assertions.assertEquals(1.0D, spline.at(0).x(), EPSILON);
        Assertions.assertEquals(90.0F, spline.at(0).yaw(), EPSILON);
        Assertions.assertEquals(3.0D, spline.at(40).x(), EPSILON);
        Assertions.assertEquals(180.0F, spline.at(40).yaw(), EPSILON);
    }

    @Test
    void aTickPastTheEndHoldsTheLastPose() {
        Spline spline = new Spline(List.of(node(0, 64, 0, 0, 0, 20), node(0, 64, 10, 0, 0, 0)));

        Assertions.assertEquals(10.0D, spline.at(1000).z(), EPSILON);
    }

    @Test
    void theTotalIsTheSumOfEverySegmentDuration() {
        Spline spline = new Spline(List.of(
            node(0, 64, 0, 0, 0, 20), node(0, 64, 10, 0, 0, 30), node(0, 64, 20, 0, 0, 0)));

        Assertions.assertEquals(50L, spline.totalTicks());
    }

    @Test
    void yawFrom350To10TurnsThroughZero() {
        Spline spline = new Spline(List.of(
            node(0, 64, 0, 350, 0, 40), node(0, 64, 10, 10, 0, 0)));

        float yaw = spline.at(20).yaw();

        Assertions.assertEquals(0.0F, normalize(yaw), 1.0E-4F);
    }

    @Test
    void yawNeverTakesTheLongWayRound() {
        Spline spline = new Spline(List.of(
            node(0, 64, 0, 10, 0, 40), node(0, 64, 10, 350, 0, 0)));

        Assertions.assertEquals(0.0F, normalize(spline.at(20).yaw()), 1.0E-4F);
    }

    @Test
    void pitchInterpolatesLinearly() {
        Spline spline = new Spline(List.of(
            node(0, 64, 0, 0, -90, 40), node(0, 64, 10, 0, 90, 0)));

        Assertions.assertEquals(0.0F, spline.at(20).pitch(), 1.0E-4F);
    }

    @Test
    void aSingleNodePathHoldsThatPose() {
        Spline spline = new Spline(List.of(node(5, 64, 6, 45, 20, 0)));

        Assertions.assertEquals(0L, spline.totalTicks());
        Assertions.assertEquals(5.0D, spline.at(0).x(), EPSILON);
        Assertions.assertEquals(5.0D, spline.at(100).x(), EPSILON);
    }

    @Test
    void aFourNodeCurveStaysInsideTheHullOfItsControlPoints() {
        Spline spline = new Spline(List.of(
            node(0, 64, 0, 0, 0, 20), node(10, 64, 0, 0, 0, 20),
            node(10, 64, 10, 0, 0, 20), node(0, 64, 10, 0, 0, 0)));

        for (long tick = 0; tick <= spline.totalTicks(); tick++) {
            Spline.Pose pose = spline.at(tick);
            Assertions.assertTrue(pose.x() >= -3.0D && pose.x() <= 13.0D, "x escaped: " + pose.x());
            Assertions.assertTrue(pose.z() >= -3.0D && pose.z() <= 13.0D, "z escaped: " + pose.z());
        }
    }

    @Test
    void aPathNeedsAtLeastOneNode() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Spline(List.of()));
    }

    private static Spline.Node node(double x, double y, double z, float yaw, float pitch, int duration) {
        return new Spline.Node(x, y, z, yaw, pitch, duration);
    }

    private static float normalize(float yaw) {
        float wrapped = yaw % 360.0F;
        if (wrapped > 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }
}
