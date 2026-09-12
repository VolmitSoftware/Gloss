package art.arcane.gloss.marker;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class EdgeIndicatorMathTest {
    private static final double MARGIN = 0.8D;
    private static final double EPSILON = 1.0E-6D;

    @Test
    void aMarkerStraightAheadIsInsideTheFrustum() {
        EdgeIndicatorMath.Indicator indicator =
            EdgeIndicatorMath.resolve(0.0D, 0.0D, new Vector(0, 0, 10), MARGIN);

        Assertions.assertTrue(indicator.inside());
        Assertions.assertEquals(0.0D, indicator.screenX(), EPSILON);
        Assertions.assertEquals(0.0D, indicator.screenY(), EPSILON);
    }

    @Test
    void aMarkerJustInsideTheHorizontalEdgeStaysInside() {
        double halfWidthTangent = EdgeIndicatorMath.horizontalTangent();
        Vector direction = new Vector(-halfWidthTangent * 0.9D, 0, 1);

        Assertions.assertTrue(EdgeIndicatorMath.resolve(0.0D, 0.0D, direction, MARGIN).inside());
    }

    @Test
    void aMarkerJustOutsideTheHorizontalEdgeIsClamped() {
        double halfWidthTangent = EdgeIndicatorMath.horizontalTangent();
        Vector direction = new Vector(-halfWidthTangent * 1.4D, 0, 1);

        EdgeIndicatorMath.Indicator indicator = EdgeIndicatorMath.resolve(0.0D, 0.0D, direction, MARGIN);

        Assertions.assertFalse(indicator.inside());
        Assertions.assertEquals(MARGIN, indicator.screenX(), EPSILON);
        Assertions.assertEquals(EdgeIndicatorMath.Bearing.RIGHT, indicator.bearing());
    }

    @Test
    void aMarkerDirectlyBehindClampsToTheBottomCentre() {
        EdgeIndicatorMath.Indicator indicator =
            EdgeIndicatorMath.resolve(0.0D, 0.0D, new Vector(0, 0, -10), MARGIN);

        Assertions.assertFalse(indicator.inside());
        Assertions.assertEquals(0.0D, indicator.screenX(), EPSILON);
        Assertions.assertEquals(-MARGIN, indicator.screenY(), EPSILON);
        Assertions.assertEquals(EdgeIndicatorMath.Bearing.DOWN, indicator.bearing());
    }

    @Test
    void aMarkerOneHundredTwentyDegreesRightClampsToTheRightEdge() {
        double radians = Math.toRadians(120.0D);
        Vector direction = new Vector(-Math.sin(radians), 0, Math.cos(radians));

        EdgeIndicatorMath.Indicator indicator = EdgeIndicatorMath.resolve(0.0D, 0.0D, direction, MARGIN);

        Assertions.assertFalse(indicator.inside());
        Assertions.assertEquals(MARGIN, indicator.screenX(), EPSILON);
        Assertions.assertEquals(0.0D, indicator.screenY(), EPSILON);
        Assertions.assertEquals(EdgeIndicatorMath.Bearing.RIGHT, indicator.bearing());
    }

    @Test
    void aMarkerHighAboveClampsToTheTopEdge() {
        EdgeIndicatorMath.Indicator indicator =
            EdgeIndicatorMath.resolve(0.0D, 0.0D, new Vector(0, 10, 1), MARGIN);

        Assertions.assertFalse(indicator.inside());
        Assertions.assertEquals(MARGIN, indicator.screenY(), EPSILON);
        Assertions.assertEquals(EdgeIndicatorMath.Bearing.UP, indicator.bearing());
    }

    @Test
    void theYawRotatesTheFrustumWithTheEye() {
        Vector west = new Vector(-10, 0, 0);

        Assertions.assertFalse(EdgeIndicatorMath.resolve(0.0D, 0.0D, west, MARGIN).inside());
        Assertions.assertTrue(EdgeIndicatorMath.resolve(90.0D, 0.0D, west, MARGIN).inside());
    }

    @Test
    void theOffsetPutsTheIndicatorOnThePlaneInFrontOfTheEye() {
        EdgeIndicatorMath.Indicator ahead =
            EdgeIndicatorMath.resolve(0.0D, 0.0D, new Vector(0, 0, 10), MARGIN);

        Vector offset = EdgeIndicatorMath.offset(0.0D, 0.0D, ahead);

        Assertions.assertEquals(0.0D, offset.getX(), EPSILON);
        Assertions.assertEquals(0.0D, offset.getY(), EPSILON);
        Assertions.assertEquals(EdgeIndicatorMath.PLANE_DISTANCE, offset.getZ(), EPSILON);
    }

    @Test
    void theOffsetOfARightEdgeIndicatorPointsWest() {
        EdgeIndicatorMath.Indicator right = EdgeIndicatorMath.resolve(0.0D, 0.0D,
            new Vector(-EdgeIndicatorMath.horizontalTangent() * 4.0D, 0, 1), MARGIN);

        Vector offset = EdgeIndicatorMath.offset(0.0D, 0.0D, right);

        Assertions.assertTrue(offset.getX() < 0.0D);
        Assertions.assertEquals(EdgeIndicatorMath.PLANE_DISTANCE, offset.getZ(), EPSILON);
    }

    @Test
    void aZeroMarginPinsTheIndicatorToTheCentre() {
        EdgeIndicatorMath.Indicator indicator =
            EdgeIndicatorMath.resolve(0.0D, 0.0D, new Vector(0, 0, -10), 0.0D);

        Assertions.assertEquals(0.0D, indicator.screenX(), EPSILON);
        Assertions.assertEquals(0.0D, indicator.screenY(), EPSILON);
    }
}
