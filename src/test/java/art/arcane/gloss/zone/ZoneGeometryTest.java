package art.arcane.gloss.zone;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class ZoneGeometryTest {
    private static final double EPSILON = 1.0E-6D;

    @Test
    void aCuboidHasTwelveEdges() {
        Assertions.assertEquals(12, ZoneGeometry.edges(cuboid()).size());
    }

    @Test
    void aCuboidHasSixFaces() {
        List<ZoneGeometry.Face> faces = ZoneGeometry.faces(cuboid());

        Assertions.assertEquals(6, faces.size());
        Assertions.assertEquals(1, faces.stream().filter(face -> face.normal().getY() > 0.5D).count());
        Assertions.assertEquals(1, faces.stream().filter(face -> face.normal().getY() < -0.5D).count());
    }

    @Test
    void aCylinderDiscretizesIntoTwoRingsAndFourVerticals() {
        List<ZoneGeometry.Segment> edges = ZoneGeometry.edges(cylinder());

        Assertions.assertEquals(ZoneGeometry.CYLINDER_SEGMENTS * 2 + 4, edges.size());
    }

    @Test
    void everyCylinderRingPointSitsOnTheRadius() {
        for (ZoneGeometry.Segment segment : ZoneGeometry.edges(cylinder())) {
            if (Math.abs(segment.from().getY() - segment.to().getY()) > EPSILON) {
                continue;
            }
            double radius = Math.hypot(segment.from().getX() - 10.0D, segment.from().getZ() - 10.0D);
            Assertions.assertEquals(8.0D, radius, 1.0E-9D);
        }
    }

    @Test
    void aPolygonHasTopAndBottomRingsPlusOneVerticalPerPoint() {
        List<ZoneGeometry.Segment> edges = ZoneGeometry.edges(polygon());

        Assertions.assertEquals(3 * 3, edges.size());
    }

    @Test
    void aPolygonHasOneWallFacePerEdge() {
        Assertions.assertEquals(3, ZoneGeometry.faces(polygon()).size());
    }

    @Test
    void samplingAnEdgeRespectsTheSpacing() {
        ZoneGeometry.Segment segment = new ZoneGeometry.Segment(new Vector(0, 0, 0), new Vector(0, 0, 10));

        List<Vector> points = ZoneGeometry.sample(segment, 2.0D);

        Assertions.assertEquals(6, points.size());
        Assertions.assertEquals(0.0D, points.getFirst().getZ(), EPSILON);
        Assertions.assertEquals(10.0D, points.getLast().getZ(), EPSILON);
        Assertions.assertEquals(2.0D, points.get(1).getZ(), EPSILON);
    }

    @Test
    void aZeroLengthEdgeStillSamplesItsOwnPoint() {
        List<Vector> points = ZoneGeometry.sample(
            new ZoneGeometry.Segment(new Vector(1, 2, 3), new Vector(1, 2, 3)), 1.0D);

        Assertions.assertEquals(1, points.size());
    }

    @Test
    void aFaceFacesAnEyeOnItsOutsideOnly() {
        ZoneGeometry.Face north = ZoneGeometry.faces(cuboid()).stream()
            .filter(face -> face.normal().getZ() < -0.5D).findFirst().orElseThrow();

        Assertions.assertTrue(ZoneGeometry.facing(north, new Vector(120, 70, 50)));
        Assertions.assertFalse(ZoneGeometry.facing(north, new Vector(120, 70, 120)));
    }

    @Test
    void containsAnswersForEveryShapeKind() {
        Assertions.assertTrue(ZoneGeometry.contains(cuboid(), new Vector(120, 70, 120)));
        Assertions.assertFalse(ZoneGeometry.contains(cuboid(), new Vector(80, 70, 120)));
        Assertions.assertTrue(ZoneGeometry.contains(cylinder(), new Vector(12, 70, 10)));
        Assertions.assertFalse(ZoneGeometry.contains(cylinder(), new Vector(30, 70, 10)));
        Assertions.assertTrue(ZoneGeometry.contains(polygon(), new Vector(2, 70, 2)));
        Assertions.assertFalse(ZoneGeometry.contains(polygon(), new Vector(-5, 70, 2)));
    }

    @Test
    void theCentreOfACuboidIsItsMidpoint() {
        Vector centre = ZoneGeometry.centre(cuboid());

        Assertions.assertEquals(120.0D, centre.getX(), EPSILON);
        Assertions.assertEquals(70.0D, centre.getY(), EPSILON);
        Assertions.assertEquals(120.0D, centre.getZ(), EPSILON);
    }

    static ZoneShape cuboid() {
        return new ZoneShape("cuboid", "world", new double[]{100, 60, 100}, new double[]{140, 80, 140},
            null, null, null, null, null, null, null);
    }

    static ZoneShape cylinder() {
        return new ZoneShape("cylinder", "world", null, null, new double[]{10, 10}, 8.0D, 60.0D, 80.0D,
            null, null, null);
    }

    static ZoneShape polygon() {
        return new ZoneShape("polygon", "world", null, null, null, null, 60.0D, 80.0D,
            List.of(new double[]{0, 0}, new double[]{10, 0}, new double[]{0, 10}), null, null);
    }
}
