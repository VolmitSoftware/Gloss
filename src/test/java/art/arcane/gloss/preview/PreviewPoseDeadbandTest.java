package art.arcane.gloss.preview;

import art.arcane.gloss.menu.CharacterizationSupport;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The reposition budget: a card anchored to the viewer's eye must not re-place every one of its
 * thirty-odd display entities because the mouse moved a hundredth of a degree.
 *
 * <p>{@code PreviewPoseGateTest} pins that a real move still opens the gate. This pins the two
 * limits added on top of it — a deadband on how far the eye may drift from the pose the card was
 * placed at, and a cap on how often the gate is consulted at all.
 */
public class PreviewPoseDeadbandTest {

  private static final Vector TARGET_CENTER = new Vector(3.5D, 65.5D, 7.5D);

  @Test
  public void aMouseTwitchInsideTheDeadbandNeverReposition() throws ReflectiveOperationException {
    AtomicReference<Location> pose = new AtomicReference<>(pose(1.0D, 66.62D, 2.0D, 35.0F, -10.0F));
    ContainerPreview preview = preview(pose);
    recompute(preview);
    assertTrue(poseMoved(preview));

    assertStill(preview, pose, pose(1.0D, 66.62D, 2.0D, 35.4F, -10.0F), "a sub-half-degree turn");
    assertStill(preview, pose, pose(1.0D, 66.62D, 2.0D, 35.4F, -10.3F), "a sub-half-degree pitch");
    assertStill(preview, pose, pose(1.005D, 66.625D, 2.005D, 35.4F, -10.3F), "a centimetre of walk");
  }

  @Test
  public void driftPastTheDeadbandIsMeasuredFromThePlacedPoseNotThePreviousTick()
      throws ReflectiveOperationException {
    AtomicReference<Location> pose = new AtomicReference<>(pose(1.0D, 66.62D, 2.0D, 0.0F, 0.0F));
    ContainerPreview preview = preview(pose);
    recompute(preview);
    assertTrue(poseMoved(preview));

    // Steps of a fifth of a degree: never a move against the previous tick, plainly a move against
    // the pose the card is actually sitting at.
    assertStill(preview, pose, pose(1.0D, 66.62D, 2.0D, 0.2F, 0.0F), "one step of drift");
    assertStill(preview, pose, pose(1.0D, 66.62D, 2.0D, 0.4F, 0.0F), "two steps of drift");

    pose.set(pose(1.0D, 66.62D, 2.0D, 0.6F, 0.0F));
    recompute(preview);
    assertTrue("drift past the deadband must place the card again", poseMoved(preview));
  }

  @Test
  public void aYawWrapIsNotAFullTurn() throws ReflectiveOperationException {
    AtomicReference<Location> pose = new AtomicReference<>(pose(1.0D, 66.62D, 2.0D, 359.9F, 0.0F));
    ContainerPreview preview = preview(pose);
    recompute(preview);
    assertTrue(poseMoved(preview));

    assertStill(preview, pose, pose(1.0D, 66.62D, 2.0D, 0.1F, 0.0F), "crossing north");
  }

  @Test
  public void aMovingViewerIsFollowedOnEveryTick() throws ReflectiveOperationException {
    AtomicReference<Location> pose = new AtomicReference<>(pose(1.0D, 66.62D, 2.0D, 0.0F, 0.0F));
    ContainerPreview preview = preview(pose);
    recompute(preview);
    assertTrue(poseMoved(preview));

    // The card is head-locked: it hangs off the eye, so a turn that clears the deadband must be
    // followed on the tick it happens. Holding it back for a cadence would trail the crosshair.
    for (int tick = 1; tick <= 4; tick++) {
      pose.set(pose(1.0D, 66.62D, 2.0D, tick * 5.0F, 0.0F));
      recompute(preview);
      assertTrue("tick " + tick + " of a turn must be followed, not deferred", poseMoved(preview));
    }
  }

  @Test
  public void theTeleportInterpolationIsNeverTiedToASendCadence() throws ReflectiveOperationException {
    Field field = ContainerPreview.class.getDeclaredField("TELEPORT_INTERPOLATION_TICKS");
    field.setAccessible(true);

    assertEquals("a head-locked card must catch up within a tick of the teleport arriving; a longer"
            + " interpolation is a permanent lag behind the camera, not smoothing",
        1, field.getInt(null));
  }

  // ---------------------------------------------------------------------
  // Plumbing
  // ---------------------------------------------------------------------

  private static void assertStill(ContainerPreview preview, AtomicReference<Location> pose,
                                  Location moved, String what) {
    pose.set(moved);
    recompute(preview);
    assertFalse(what + " must not re-place the card", poseMoved(preview));
  }

  private static boolean poseMoved(ContainerPreview preview) {
    return (boolean) CharacterizationSupport.invoke(preview, "poseMoved", new Class<?>[0]);
  }

  private static void recompute(ContainerPreview preview) {
    CharacterizationSupport.invoke(preview, "recomputeAnchor", new Class<?>[0]);
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
          case "getUniqueId" -> UUID.fromString("00000000-0000-0000-0000-000000000907");
          case "getName" -> "deadband";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "Player[deadband]";
          default -> throw new UnsupportedOperationException(
              "the pose deadband touched Player#" + method.getName());
        });
  }
}
