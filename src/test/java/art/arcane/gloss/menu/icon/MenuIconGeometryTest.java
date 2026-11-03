package art.arcane.gloss.menu.icon;

import art.arcane.gloss.menu.MenuTransform;
import art.arcane.gloss.util.common.math.CollisionPlane;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MenuIconGeometryTest {

  @Test
  public void textPlaneTracksTheVisibleGlyphCenterAtEveryScale() {
    Location anchor = new Location(null, 1D, 2D, 3D);

    Vector normalScale = MenuIcon.textBoundingBoxCenter(transform(1F), anchor);
    Vector largeScale = MenuIcon.textBoundingBoxCenter(transform(2.5F), anchor);

    assertEquals(1D, normalScale.getX(), 0D);
    assertEquals(1.675D, normalScale.getY(), 0.000001D);
    assertEquals(3D, normalScale.getZ(), 0D);
    assertEquals(1.1875D, largeScale.getY(), 0.000001D);
  }

  @Test
  public void lookingAtVisibleTextHitsWithoutAimingAtTheGapAboveIt() {
    Location anchor = new Location(null, 0D, 0D, 3D);
    Vector visibleCenter = MenuIcon.textBoundingBoxCenter(transform(1F), anchor);
    CollisionPlane plane = new CollisionPlane(visibleCenter, 1F, MenuIcon.NAMETAG_SIZE);
    Vector origin = new Vector(0D, 0D, 0D);

    assertTrue(plane.isLookingAt(origin, visibleCenter.clone().subtract(origin).normalize()));
    assertFalse(plane.isLookingAt(origin, anchor.toVector().subtract(origin).normalize()));
  }

  @Test
  public void blockDisplayUsesItemComparableAutomaticGeometry() {
    assertEquals(0.75F, BlockMenuIcon.DISPLAY_SIZE, 0F);
    assertEquals(-0.05F, BlockMenuIcon.VERTICAL_OFFSET, 0F);
  }

  @Test
  public void itemsDrawOnTheirCollisionPlaneCenter() {
    assertEquals(BlockMenuIcon.VERTICAL_OFFSET, ItemMenuIcon.itemOffset(1).getY(), 0.000001D);
    assertEquals(0D, ItemMenuIcon.itemOffset(1).getZ(), 0D);
    assertEquals(BlockMenuIcon.VERTICAL_OFFSET, ItemMenuIcon.blockOffset().getY(), 0.000001D);
  }

  @Test
  public void aStackLiftsAFlatItemAndABlockSitsForward() {
    assertEquals(BlockMenuIcon.VERTICAL_OFFSET + ItemMenuIcon.COUNT_LIFT, ItemMenuIcon.itemOffset(2).getY(), 0.000001D);
    assertEquals(ItemMenuIcon.BLOCK_DEPTH, ItemMenuIcon.blockOffset().getZ(), 0.000001D);
  }

  private static MenuTransform transform(float scale) {
    return new MenuTransform(new Location(null, 0D, 0D, 0D), new Vector(), 0F, 0F, 0F, scale);
  }
}
