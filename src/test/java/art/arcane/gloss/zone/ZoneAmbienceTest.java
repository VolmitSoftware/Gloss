package art.arcane.gloss.zone;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

class ZoneAmbienceTest {
    @Test
    void everyPointLandsInsideTheZoneAndWithinTheRadius() {
        ZoneShape shape = ZoneGeometryTest.cuboid();
        Vector viewer = new Vector(120, 70, 120);

        List<Vector> points = ZoneAmbience.points(shape, viewer, 12.0D, 8, new Random(7L));

        Assertions.assertFalse(points.isEmpty());
        for (Vector point : points) {
            Assertions.assertTrue(ZoneGeometry.contains(shape, point), "outside the zone: " + point);
            Assertions.assertTrue(point.distance(viewer) <= 12.0D + 1.0E-6D, "outside the radius: " + point);
        }
    }

    @Test
    void neverEmitsMoreThanTheRequestedBudget() {
        List<Vector> points = ZoneAmbience.points(ZoneGeometryTest.cuboid(),
            new Vector(120, 70, 120), 12.0D, 4, new Random(11L));

        Assertions.assertTrue(points.size() <= 4);
    }

    @Test
    void aViewerOutsideTheZoneGetsNothing() {
        List<Vector> points = ZoneAmbience.points(ZoneGeometryTest.cuboid(),
            new Vector(500, 70, 500), 12.0D, 8, new Random(3L));

        Assertions.assertEquals(List.of(), points);
    }

    @Test
    void aZeroBudgetEmitsNothing() {
        Assertions.assertEquals(List.of(), ZoneAmbience.points(ZoneGeometryTest.cuboid(),
            new Vector(120, 70, 120), 12.0D, 0, new Random(3L)));
    }

    @Test
    void theViewerBudgetIsSharedAcrossZones() {
        ZoneBudget budget = new ZoneBudget(10);

        Assertions.assertEquals(6, budget.take(6));
        Assertions.assertEquals(4, budget.take(6));
        Assertions.assertEquals(0, budget.take(6));
    }

    @Test
    void resettingTheBudgetRefillsIt() {
        ZoneBudget budget = new ZoneBudget(10);
        budget.take(10);

        budget.reset();

        Assertions.assertEquals(10, budget.take(10));
    }

    @Test
    void aCylinderAmbiencePointStaysInsideTheCylinder() {
        ZoneShape shape = ZoneGeometryTest.cylinder();

        List<Vector> points = ZoneAmbience.points(shape, new Vector(10, 70, 10), 6.0D, 16, new Random(5L));

        Assertions.assertFalse(points.isEmpty());
        for (Vector point : points) {
            Assertions.assertTrue(ZoneGeometry.contains(shape, point));
        }
    }
}
