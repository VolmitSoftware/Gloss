package art.arcane.gloss.preview;

import art.arcane.gloss.menu.CharacterizationSupport;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * Characterization pins for the preview reposition math (plan item T2/D1): every rendered element
 * position produced by {@code ContainerPreview.at(..)} is a PURE FUNCTION of the viewer's eye pose
 * {@code (x, y, z, yaw, pitch)} and the applied scale. This is the precondition that makes a
 * pose-gate ("same pose in ⇒ skip the reposition loop entirely") a bit-identical optimization:
 * returning to a pose must reproduce the exact same coordinates, byte for byte.
 */
public class CharacterizationPreviewPoseTest {

  private static final Vector TARGET_CENTER = new Vector(3.5D, 65.5D, 7.5D);
  private static final double[][] LAYOUT_POINTS = {
      {0.0D, 0.0D, 0.0D},
      {24.0D, -13.0D, 0.0D},
      {5.0D, 10.0D, 3.0D},
      {-8.0D, 4.0D, 1.5D},
  };

  @Test
  public void theSamePoseReproducesBitIdenticalPositions() throws ReflectiveOperationException {
    AtomicReference<Location> pose = new AtomicReference<>(pose(1.0D, 66.62D, 2.0D, 35.0F, -10.0F));
    ContainerPreview preview = preview(pose);

    recompute(preview);
    List<Location> first = capture(preview);
    recompute(preview);
    List<Location> secondSamePose = capture(preview);

    pose.set(pose(4.0D, 67.0D, -3.0D, 190.0F, 25.0F));
    recompute(preview);
    List<Location> moved = capture(preview);

    pose.set(pose(1.0D, 66.62D, 2.0D, 35.0F, -10.0F));
    recompute(preview);
    List<Location> back = capture(preview);

    assertPositions("recomputing without moving must be a no-op", first, secondSamePose);
    assertPositions("returning to a pose must reproduce the original positions exactly "
        + "(this is what lets a pose gate skip the reposition loop bit-identically)", first, back);
    assertNotEquals("a moved pose must actually move the layout",
        first.get(0).toVector(), moved.get(0).toVector());
  }

  @Test
  public void positionsFaceThePlaneDerivedFromTheEyePose() throws ReflectiveOperationException {
    AtomicReference<Location> pose = new AtomicReference<>(pose(0.0D, 66.0D, 0.0D, 55.0F, 12.5F));
    ContainerPreview preview = preview(pose);

    recompute(preview);
    Location placed = at(preview, LAYOUT_POINTS[1]);

    assertEquals("element yaw is the eye yaw turned back at the viewer", 55.0F - 180.0F,
        placed.getYaw(), 0.0F);
    assertEquals("element pitch mirrors the eye pitch", -12.5F, placed.getPitch(), 0.0F);
  }

  @Test
  public void theAppliedScaleIsPartOfThePositionInput() throws ReflectiveOperationException {
    AtomicReference<Location> pose = new AtomicReference<>(pose(1.0D, 66.62D, 2.0D, 35.0F, -10.0F));
    ContainerPreview preview = preview(pose);
    recompute(preview);
    Location atFullScale = at(preview, LAYOUT_POINTS[1]);

    CharacterizationSupport.setField(preview, "appliedScale", 0.5D);
    Location atHalfScale = at(preview, LAYOUT_POINTS[1]);

    assertNotEquals("a pose gate must treat the applied scale as part of its key: the same pose "
        + "at a different scale produces different positions",
        atFullScale.toVector(), atHalfScale.toVector());
  }

  // ---------------------------------------------------------------------
  // Plumbing
  // ---------------------------------------------------------------------

  private static void assertPositions(String message, List<Location> expected, List<Location> actual) {
    assertEquals(expected.size(), actual.size());
    for (int i = 0; i < expected.size(); i++) {
      Location want = expected.get(i);
      Location got = actual.get(i);
      assertEquals(message + " [x, point " + i + "]", want.getX(), got.getX(), 0.0D);
      assertEquals(message + " [y, point " + i + "]", want.getY(), got.getY(), 0.0D);
      assertEquals(message + " [z, point " + i + "]", want.getZ(), got.getZ(), 0.0D);
      assertEquals(message + " [yaw, point " + i + "]", want.getYaw(), got.getYaw(), 0.0F);
      assertEquals(message + " [pitch, point " + i + "]", want.getPitch(), got.getPitch(), 0.0F);
    }
  }

  private static List<Location> capture(ContainerPreview preview) {
    List<Location> positions = new ArrayList<>();
    for (double[] point : LAYOUT_POINTS) {
      positions.add(at(preview, point));
    }
    return positions;
  }

  private static void recompute(ContainerPreview preview) {
    CharacterizationSupport.invoke(preview, "recomputeAnchor", new Class<?>[0]);
  }

  private static Location at(ContainerPreview preview, double[] layoutPoint) {
    return (Location) CharacterizationSupport.invoke(preview, "at",
        new Class<?>[]{double[].class}, (Object) layoutPoint);
  }

  private static ContainerPreview preview(AtomicReference<Location> pose)
      throws ReflectiveOperationException {
    Constructor<ContainerPreview> constructor = ContainerPreview.class.getDeclaredConstructor(
        Player.class, Block.class, Entity.class, Vector.class, List.class, boolean.class);
    constructor.setAccessible(true);
    return constructor.newInstance(player(pose), null, null, TARGET_CENTER,
        List.<PreviewElement>of(), true);
  }

  private static Location pose(double x, double y, double z, float yaw, float pitch) {
    return new Location(null, x, y, z, yaw, pitch);
  }

  private static Player player(AtomicReference<Location> pose) {
    return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getEyeLocation" -> pose.get().clone();
          case "getUniqueId" -> UUID.fromString("00000000-0000-0000-0000-000000000905");
          case "getName" -> "poser";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "Player[poser]";
          default -> throw new UnsupportedOperationException(
              "the reposition math touched Player#" + method.getName());
        });
  }
}
