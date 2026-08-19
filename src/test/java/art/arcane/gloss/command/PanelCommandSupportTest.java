package art.arcane.gloss.command;

import art.arcane.gloss.panel.PanelDefinition;
import art.arcane.gloss.panel.PanelFollowMode;
import art.arcane.gloss.panel.PanelFollowRotation;
import art.arcane.gloss.panel.PanelFollowTransform;
import art.arcane.gloss.panel.PanelTransform;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public class PanelCommandSupportTest {
  private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000401");

  @Test
  public void transformArgumentsAcceptAbsoluteAndTildeRelativeValues() {
    PanelTransform original = transform(10D, 20D, 30D, 40D, 50D, 60D, 1D);

    PanelTransform moved = PanelCommandSupport.move(original, "~2.5", "17", "~");
    PanelTransform rotated = PanelCommandSupport.rotate(moved, "~-10", "0", "~15");
    PanelTransform scaled = PanelCommandSupport.scale(rotated, "~0.5");

    assertEquals(12.5D, scaled.x(), 0.000001D);
    assertEquals(17D, scaled.y(), 0.000001D);
    assertEquals(30D, scaled.z(), 0.000001D);
    assertEquals(30D, scaled.yaw(), 0.000001D);
    assertEquals(0D, scaled.pitch(), 0.000001D);
    assertEquals(75D, scaled.roll(), 0.000001D);
    assertEquals(1.5D, scaled.scale(), 0.000001D);
    assertThrows(IllegalArgumentException.class,
        () -> PanelCommandSupport.move(original, "NaN", "0", "0"));
    assertThrows(IllegalArgumentException.class,
        () -> PanelCommandSupport.scale(original, "~100"));
  }

  @Test
  public void followAndUnfollowPreserveTheEffectiveWorldPose() {
    World world = world();
    Location target = new Location(world, 20D, 70D, -4D, 85F, 25F);
    PanelTransform absolute = transform(24D, 72D, -7D, 115D, 35D, 12D, 1.25D);
    PanelDefinition initial = PanelDefinition.create("spawn/sign", "main", absolute);
    UUID targetId = UUID.fromString("00000000-0000-0000-0000-000000000402");

    PanelDefinition following = PanelCommandSupport.follow(
        initial, absolute, target, targetId, PanelFollowRotation.FULL);

    assertEquals(PanelFollowMode.PLAYER, following.follow().mode());
    assertNotEquals(absolute, following.transform());
    assertTransform(absolute, PanelFollowTransform.resolve(following, target));

    Location movedTarget = new Location(world, 35D, 74D, 9D, -30F, -10F);
    PanelTransform effective = PanelFollowTransform.resolve(following, movedTarget);
    PanelDefinition unfollowed = PanelCommandSupport.unfollow(following, effective);

    assertEquals(PanelFollowMode.NONE, unfollowed.follow().mode());
    assertTransform(effective, unfollowed.transform());
  }

  @Test
  public void effectiveWorldEditsAreReencodedForFollowingBoards() {
    World world = world();
    Location target = new Location(world, 8D, 64D, 8D, 90F, 0F);
    PanelTransform absolute = transform(6D, 66D, 8D, 100D, 5D, 3D, 1D);
    PanelDefinition following = PanelCommandSupport.follow(
        PanelDefinition.create("moving", "main", absolute),
        absolute,
        target,
        UUID.randomUUID(),
        PanelFollowRotation.YAW
    );
    PanelTransform changedEffective = PanelCommandSupport.move(absolute, "~4", "80", "~-2");

    PanelDefinition reencoded = PanelCommandSupport.reencodeEffectiveTransform(
        following, changedEffective, target);

    assertTransform(changedEffective, PanelFollowTransform.resolve(reencoded, target));
  }

  @Test
  public void alignmentCopiesOnlyTheSelectedWorldAxes() {
    PanelTransform current = transform(1D, 2D, 3D, 10D, 20D, 30D, 2D);
    PanelTransform reference = transform(8D, 9D, 10D, 40D, 50D, 60D, 3D);

    PanelTransform aligned = PanelCommandSupport.align(current, reference, "xz");

    assertEquals(8D, aligned.x(), 0.000001D);
    assertEquals(2D, aligned.y(), 0.000001D);
    assertEquals(10D, aligned.z(), 0.000001D);
    assertEquals(10D, aligned.yaw(), 0.000001D);
    assertEquals(2D, aligned.scale(), 0.000001D);
    assertThrows(IllegalArgumentException.class,
        () -> PanelCommandSupport.align(current, reference, "pitch"));
  }

  @Test
  public void copyCreatesFreshIdentityAtTheInitialRevision() {
    PanelDefinition source = PanelDefinition.create("source", "main", transform(
        1D, 2D, 3D, 4D, 5D, 6D, 1D));

    PanelDefinition copy = PanelCommandSupport.copy(source, "folder/copy");

    assertEquals("folder/copy", copy.id());
    assertNotEquals(source.uuid(), copy.uuid());
    assertEquals(PanelDefinition.INITIAL_REVISION, copy.revision());
    assertEquals(source.transform(), copy.transform());
  }

  private static PanelTransform transform(double x, double y, double z, double yaw,
                                          double pitch, double roll, double scale) {
    return new PanelTransform("example:world", WORLD_UUID, x, y, z, yaw, pitch, roll, scale);
  }

  private static void assertTransform(PanelTransform expected, PanelTransform actual) {
    assertEquals(expected.worldKey(), actual.worldKey());
    assertEquals(expected.worldUuid(), actual.worldUuid());
    assertEquals(expected.x(), actual.x(), 0.000001D);
    assertEquals(expected.y(), actual.y(), 0.000001D);
    assertEquals(expected.z(), actual.z(), 0.000001D);
    assertEquals(expected.yaw(), actual.yaw(), 0.000001D);
    assertEquals(expected.pitch(), actual.pitch(), 0.000001D);
    assertEquals(expected.roll(), actual.roll(), 0.000001D);
    assertEquals(expected.scale(), actual.scale(), 0.000001D);
  }

  private static World world() {
    return (World) Proxy.newProxyInstance(
        PanelCommandSupportTest.class.getClassLoader(),
        new Class<?>[]{World.class},
        (proxy, method, arguments) -> switch (method.getName()) {
          case "getUID" -> WORLD_UUID;
          case "getKey" -> new NamespacedKey("example", "world");
          case "getName" -> "world";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == arguments[0];
          case "toString" -> "world";
          default -> defaultValue(method.getReturnType());
        }
    );
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == float.class) {
      return 0F;
    }
    if (type == double.class) {
      return 0D;
    }
    if (type == char.class) {
      return '\0';
    }
    return null;
  }
}
