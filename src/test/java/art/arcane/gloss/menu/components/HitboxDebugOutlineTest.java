package art.arcane.gloss.menu.components;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HitboxDebugOutlineTest {
    private static final Vector CENTER = new Vector(10, 20, 30);
    private static final Vector UP = new Vector(0, 1, 0);
    private static final Vector RIGHT = new Vector(1, 0, 0);
    private static final Vector NORMAL = new Vector(0, 0, 1);

    @Test
    void anOutlineSpacesItsDotsAQuarterBlockApart() {
        List<Vector> points = ClickableComponent.outlinePoints(CENTER, UP, RIGHT, 1F, 0.5F);

        assertEquals(12, points.size());
    }

    @Test
    void everyCornerIsDrawn() {
        List<Vector> points = ClickableComponent.outlinePoints(CENTER, UP, RIGHT, 1F, 0.5F);

        assertTrue(points.contains(new Vector(9.5, 19.75, 30)));
        assertTrue(points.contains(new Vector(10.5, 19.75, 30)));
        assertTrue(points.contains(new Vector(10.5, 20.25, 30)));
        assertTrue(points.contains(new Vector(9.5, 20.25, 30)));
    }

    @Test
    void aTinyPlaneStillDrawsOnlyItsCorners() {
        List<Vector> points = ClickableComponent.outlinePoints(CENTER, UP, RIGHT, 0.01F, 0.01F);

        assertEquals(4, points.size());
    }

    @Test
    void theNormalIsAShortSegment() {
        List<Vector> points = ClickableComponent.normalPoints(CENTER, NORMAL);

        assertEquals(3, points.size());
        assertEquals(30.6D, points.getLast().getZ(), 1.0E-9D);
    }
}
