package art.arcane.gloss.preview;

import art.arcane.gloss.menu.CharacterizationSupport;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The reposition pose gate: a preview only re-places its elements when something {@code at(..)}
 * reads has actually changed. {@code CharacterizationPreviewPoseTest} pins the precondition — the
 * positions are a pure function of the eye pose and the applied scale — and this pins the gate
 * built on it, including that a "not moved" verdict really does imply identical coordinates.
 */
public class PreviewPoseGateTest {

  private static final Vector TARGET_CENTER = new Vector(3.5D, 65.5D, 7.5D);
  private static final double[] LAYOUT_POINT = {24.0D, -13.0D, 3.0D};

  @Test
  public void theFirstPassAlwaysRepositionsAndAStillViewerNeverDoes() throws ReflectiveOperationException {
    AtomicReference<Location> pose = new AtomicReference<>(pose(1.0D, 66.62D, 2.0D, 35.0F, -10.0F));
    ContainerPreview preview = preview(pose);

    recompute(preview);
    assertTrue("nothing has been placed yet, so the first pass must place it", poseMoved(preview));
    Location placed = at(preview);

    recompute(preview);
    assertFalse("a viewer who has not moved must not be repositioned", poseMoved(preview));
    recompute(preview);
    assertFalse("and must keep not being repositioned", poseMoved(preview));

    assertSamePosition("skipping the pass is only sound because the position is unchanged",
        placed, at(preview));
  }

  @Test
  public void everyComponentOfTheEyePoseOpensTheGate() throws ReflectiveOperationException {
    AtomicReference<Location> pose = new AtomicReference<>(pose(1.0D, 66.62D, 2.0D, 35.0F, -10.0F));
    ContainerPreview preview = preview(pose);
    recompute(preview);
    poseMoved(preview);

    assertGateOpens(preview, pose, pose(1.5D, 66.62D, 2.0D, 35.0F, -10.0F), "x");
    assertGateOpens(preview, pose, pose(1.5D, 66.70D, 2.0D, 35.0F, -10.0F), "y");
    assertGateOpens(preview, pose, pose(1.5D, 66.70D, 2.5D, 35.0F, -10.0F), "z");
    assertGateOpens(preview, pose, pose(1.5D, 66.70D, 2.5D, 36.0F, -10.0F), "yaw");
    assertGateOpens(preview, pose, pose(1.5D, 66.70D, 2.5D, 36.0F, -11.0F), "pitch");
  }

  @Test
  public void theAppliedScaleOpensTheGateOnItsOwn() throws ReflectiveOperationException {
    AtomicReference<Location> pose = new AtomicReference<>(pose(1.0D, 66.62D, 2.0D, 35.0F, -10.0F));
    ContainerPreview preview = preview(pose);
    recompute(preview);
    poseMoved(preview);
    assertFalse(poseMoved(preview));

    CharacterizationSupport.setField(preview, "appliedScale", 0.5D);

    assertTrue("a rescale moves every element even from an unchanged pose", poseMoved(preview));
    assertFalse("and settles again once it has been applied", poseMoved(preview));
  }

  // ---------------------------------------------------------------------
  // Plumbing
  // ---------------------------------------------------------------------

  private static void assertGateOpens(ContainerPreview preview, AtomicReference<Location> pose,
                                      Location moved, String component) {
    pose.set(moved);
    recompute(preview);
    assertTrue("a change in the eye " + component + " must reposition", poseMoved(preview));
    recompute(preview);
    assertFalse("and must settle again on the next pass", poseMoved(preview));
  }

  private static void assertSamePosition(String message, Location expected, Location actual) {
    assertEquals(message + " [x]", expected.getX(), actual.getX(), 0.0D);
    assertEquals(message + " [y]", expected.getY(), actual.getY(), 0.0D);
    assertEquals(message + " [z]", expected.getZ(), actual.getZ(), 0.0D);
    assertEquals(message + " [yaw]", expected.getYaw(), actual.getYaw(), 0.0F);
    assertEquals(message + " [pitch]", expected.getPitch(), actual.getPitch(), 0.0F);
  }

  private static boolean poseMoved(ContainerPreview preview) {
    return (boolean) CharacterizationSupport.invoke(preview, "poseMoved", new Class<?>[0]);
  }

  private static void recompute(ContainerPreview preview) {
    CharacterizationSupport.invoke(preview, "recomputeAnchor", new Class<?>[0]);
  }

  private static Location at(ContainerPreview preview) {
    return (Location) CharacterizationSupport.invoke(preview, "at",
        new Class<?>[]{double[].class}, (Object) LAYOUT_POINT);
  }

  private static ContainerPreview preview(AtomicReference<Location> pose)
      throws ReflectiveOperationException {
    Constructor<ContainerPreview> constructor = ContainerPreview.class.getDeclaredConstructor(
        Player.class, Block.class, Entity.class, Vector.class, List.class, List.class, boolean.class);
    constructor.setAccessible(true);
    return constructor.newInstance(player(pose), null, null, TARGET_CENTER,
        List.<PreviewElement>of(), List.of(), true);
  }

  private static Location pose(double x, double y, double z, float yaw, float pitch) {
    return new Location(null, x, y, z, yaw, pitch);
  }

  private static Player player(AtomicReference<Location> pose) {
    return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getEyeLocation" -> pose.get().clone();
          case "getUniqueId" -> UUID.fromString("00000000-0000-0000-0000-000000000906");
          case "getName" -> "gated";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "Player[gated]";
          default -> throw new UnsupportedOperationException(
              "the pose gate touched Player#" + method.getName());
        });
  }
}
