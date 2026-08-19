package art.arcane.gloss.menu.icon;

import art.arcane.gloss.config.icon.IconBillboard;
import art.arcane.gloss.util.common.math.CollisionPlane;
import org.bukkit.util.Vector;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MenuIconBillboardGeometryTest {

  @Test
  public void centeredBillboardTurnsTheClickPlaneTowardTheViewer() {
    CollisionPlane plane = sidewaysPlane();
    Vector viewer = new Vector(0D, 0D, 0D);

    MenuIcon.orientBillboardPlane(plane, IconBillboard.CENTER, viewer);

    assertTrue(plane.isLookingAt(viewer, new Vector(0D, 0D, 1D)));
  }

  @Test
  public void verticalBillboardKeepsItsUpAxisVertical() {
    CollisionPlane plane = sidewaysPlane();
    Vector viewer = new Vector(0D, 3D, 0D);

    MenuIcon.orientBillboardPlane(plane, IconBillboard.VERTICAL, viewer);

    assertEquals(0D, plane.getUp().getX(), 0.000001D);
    assertEquals(1D, plane.getUp().getY(), 0.000001D);
    assertEquals(0D, plane.getUp().getZ(), 0.000001D);
    assertTrue(plane.isLookingAt(viewer, plane.getCenter().clone().subtract(viewer).normalize()));
  }

  @Test
  public void horizontalBillboardKeepsTheBoardRightAxis() {
    CollisionPlane plane = sidewaysPlane();
    Vector right = plane.getRight().clone();

    MenuIcon.orientBillboardPlane(plane, IconBillboard.HORIZONTAL, new Vector(0D, 3D, 0D));

    assertEquals(right.getX(), plane.getRight().getX(), 0.000001D);
    assertEquals(right.getY(), plane.getRight().getY(), 0.000001D);
    assertEquals(right.getZ(), plane.getRight().getZ(), 0.000001D);
  }

  @Test
  public void fixedBillboardPreservesTheBoardTransform() {
    CollisionPlane plane = sidewaysPlane();
    Vector up = plane.getUp().clone();
    Vector right = plane.getRight().clone();

    MenuIcon.orientBillboardPlane(plane, IconBillboard.FIXED, new Vector(0D, 0D, 0D));

    assertEquals(up, plane.getUp());
    assertEquals(right, plane.getRight());
  }

  private static CollisionPlane sidewaysPlane() {
    CollisionPlane plane = new CollisionPlane(new Vector(0D, 0D, 5D), 2F, 2F);
    plane.rotate(0F, 90F, 0F);
    return plane;
  }
}
