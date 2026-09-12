package art.arcane.gloss.zone;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class ZoneWallSplitTest {
    private static final double EPSILON = 1.0E-6D;

    @Test
    void aFaceNarrowerThanTheLimitStaysOnePanel() {
        ZoneGeometry.Face face = face(20.0D, 10.0D);

        Assertions.assertEquals(1, ZoneWalls.split(face).size());
    }

    @Test
    void aWideFaceSplitsIntoPanelsNoWiderThanTheLimit() {
        List<ZoneWalls.Panel> panels = ZoneWalls.split(face(100.0D, 10.0D));

        Assertions.assertEquals(4, panels.size());
        for (ZoneWalls.Panel panel : panels) {
            Assertions.assertTrue(panel.width() <= ZoneWalls.MAX_PANEL_WIDTH + EPSILON,
                "panel wider than the limit: " + panel.width());
        }
    }

    @Test
    void thePanelsCoverExactlyTheFaceWidth() {
        List<ZoneWalls.Panel> panels = ZoneWalls.split(face(100.0D, 10.0D));

        double total = 0.0D;
        for (ZoneWalls.Panel panel : panels) {
            total += panel.width();
        }
        Assertions.assertEquals(100.0D, total, EPSILON);
    }

    @Test
    void panelCentresMarchAlongTheFace() {
        List<ZoneWalls.Panel> panels = ZoneWalls.split(face(100.0D, 10.0D));

        Assertions.assertEquals(-37.5D, panels.getFirst().centre().getX(), EPSILON);
        Assertions.assertEquals(37.5D, panels.getLast().centre().getX(), EPSILON);
    }

    @Test
    void aCuboidNeverExceedsThePerZoneDisplayBudget() {
        List<ZoneWalls.Panel> panels = ZoneWalls.panels(ZoneGeometryTest.cuboid());

        Assertions.assertFalse(panels.isEmpty());
        Assertions.assertTrue(panels.size() <= ZoneWalls.MAX_PANELS_PER_ZONE,
            "cuboid produced " + panels.size() + " panels");
    }

    @Test
    void aHugeZoneIsTruncatedToThePerZoneDisplayBudget() {
        ZoneShape huge = new ZoneShape("cuboid", "world", new double[]{0, 0, 0},
            new double[]{512, 128, 512}, null, null, null, null, null, null, null);

        Assertions.assertEquals(ZoneWalls.MAX_PANELS_PER_ZONE, ZoneWalls.panels(huge).size());
    }

    @Test
    void aVerticalFaceRotatesItsPanelToFaceItsNormal() {
        ZoneGeometry.Face north = ZoneGeometry.faces(ZoneGeometryTest.cuboid()).stream()
            .filter(shape -> shape.normal().getZ() < -0.5D).findFirst().orElseThrow();

        Vector rotated = FaceQuaternions.rotate(FaceQuaternions.of(north.normal()), new Vector(0, 0, 1));

        Assertions.assertEquals(north.normal().getX(), rotated.getX(), 1.0E-5D);
        Assertions.assertEquals(north.normal().getY(), rotated.getY(), 1.0E-5D);
        Assertions.assertEquals(north.normal().getZ(), rotated.getZ(), 1.0E-5D);
    }

    @Test
    void aHorizontalFaceRotatesItsPanelToFaceUp() {
        Vector up = new Vector(0, 1, 0);

        Vector rotated = FaceQuaternions.rotate(FaceQuaternions.of(up), new Vector(0, 0, 1));

        Assertions.assertEquals(0.0D, rotated.getX(), 1.0E-5D);
        Assertions.assertEquals(1.0D, rotated.getY(), 1.0E-5D);
        Assertions.assertEquals(0.0D, rotated.getZ(), 1.0E-5D);
    }

    private static ZoneGeometry.Face face(double width, double height) {
        return new ZoneGeometry.Face(new Vector(0, 0, 0), new Vector(0, 0, -1),
            new Vector(1, 0, 0), new Vector(0, 1, 0), width, height);
    }
}
