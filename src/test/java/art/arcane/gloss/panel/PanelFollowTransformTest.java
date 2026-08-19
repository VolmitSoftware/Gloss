package art.arcane.gloss.panel;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public class PanelFollowTransformTest {
  private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000301");

  @Test
  public void yawFollowRotatesOffsetAndFacingWithTarget() {
    World world = world();
    PanelTransform absolute = new PanelTransform("example:world", WORLD_UUID,
        -2D, 66D, 10D, 110D, 5D, 7D, 1.5D);
    Location initialTarget = new Location(world, 0D, 64D, 10D, 90F, 0F);
    PanelTransform relative = PanelFollowTransform.relativeTo(absolute, initialTarget, PanelFollowRotation.YAW);
    PanelDefinition board = PanelDefinition.create("moving", "menu", relative)
        .withFollow(PanelFollow.player(UUID.randomUUID(), PanelFollowRotation.YAW));

    PanelTransform initial = PanelFollowTransform.resolve(board, initialTarget);
    PanelTransform turned = PanelFollowTransform.resolve(board, new Location(world, 10D, 70D, 20D, 180F, 0F));

    assertTransform(absolute, initial);
    assertEquals(10D, turned.x(), 0.000001D);
    assertEquals(72D, turned.y(), 0.000001D);
    assertEquals(18D, turned.z(), 0.000001D);
    assertEquals(-160D, turned.yaw(), 0.000001D);
  }

  @Test
  public void fullFollowAddsPitchWhileFixedOnlyTranslates() {
    World world = world();
    Location target = new Location(world, 10D, 20D, 30D, 45F, 30F);
    PanelTransform relative = new PanelTransform("example:world", WORLD_UUID,
        1D, 2D, 3D, 5D, 10D, 15D, 1D);
    UUID playerId = UUID.randomUUID();
    PanelDefinition full = PanelDefinition.create("full", "menu", relative)
        .withFollow(PanelFollow.player(playerId, PanelFollowRotation.FULL));
    PanelDefinition fixed = PanelDefinition.create("fixed", "menu", relative)
        .withFollow(PanelFollow.player(playerId, PanelFollowRotation.FIXED));

    PanelTransform fullResult = PanelFollowTransform.resolve(full, target);
    PanelTransform fixedResult = PanelFollowTransform.resolve(fixed, target);

    assertEquals(50D, fullResult.yaw(), 0.000001D);
    assertEquals(40D, fullResult.pitch(), 0.000001D);
    assertEquals(5D, fixedResult.yaw(), 0.000001D);
    assertEquals(10D, fixedResult.pitch(), 0.000001D);
    assertEquals(11D, fixedResult.x(), 0.000001D);
    assertEquals(22D, fixedResult.y(), 0.000001D);
    assertEquals(33D, fixedResult.z(), 0.000001D);
  }

  @Test
  public void fullFollowRotatesItsOffsetThroughPitchAndRoundTripsTheAttachPose() {
    World world = world();
    Location target = new Location(world, 10D, 20D, 30D, 90F, 30F);
    PanelTransform absolute = new PanelTransform("example:world", WORLD_UUID,
        8D, 19D, 34D, 105D, 40D, 5D, 1D);
    PanelTransform relative = PanelFollowTransform.relativeTo(absolute, target, PanelFollowRotation.FULL);
    PanelDefinition board = PanelDefinition.create("full-round-trip", "menu", relative)
        .withFollow(PanelFollow.player(UUID.randomUUID(), PanelFollowRotation.FULL));

    assertTransform(absolute, PanelFollowTransform.resolve(board, target));

    Location lookingDown = new Location(world, 10D, 20D, 30D, 90F, 90F);
    PanelTransform pitched = PanelFollowTransform.resolve(board, lookingDown);
    assertEquals(9.866025D, pitched.x(), 0.000001D);
    assertEquals(17.767949D, pitched.y(), 0.000001D);
    assertEquals(34D, pitched.z(), 0.000001D);
  }

  @Test
  public void capturedPoseRecomputesReloadedDefinitionWithoutALivePlayerOrWorld() {
    UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000302");
    PanelFollowPose lastPose = new PanelFollowPose(
        "example:world",
        WORLD_UUID,
        10D,
        20D,
        30D,
        90F,
        35F
    );
    PanelDefinition initial = PanelDefinition.create(
        "offline-reload",
        "menu",
        new PanelTransform("example:world", WORLD_UUID, 1D, 2D, 3D, 5D, 10D, 15D, 1D)
    ).withFollow(PanelFollow.player(playerId, PanelFollowRotation.FIXED));

    PanelTransform initialEffective = PanelFollowTransform.resolve(initial, lastPose);
    PanelDefinition reloaded = initial.withTransform(new PanelTransform(
        "example:world",
        WORLD_UUID,
        4D,
        5D,
        6D,
        15D,
        20D,
        25D,
        2D
    )).withRevision(initial.revision() + 1L);
    PanelTransform reloadedEffective = PanelFollowTransform.resolve(reloaded, lastPose);

    assertEquals(11D, initialEffective.x(), 0.000001D);
    assertEquals(22D, initialEffective.y(), 0.000001D);
    assertEquals(33D, initialEffective.z(), 0.000001D);
    assertEquals(14D, reloadedEffective.x(), 0.000001D);
    assertEquals(25D, reloadedEffective.y(), 0.000001D);
    assertEquals(36D, reloadedEffective.z(), 0.000001D);
    assertEquals(15D, reloadedEffective.yaw(), 0.000001D);
    assertEquals(20D, reloadedEffective.pitch(), 0.000001D);
    assertEquals(initial.uuid(), reloaded.uuid());
    assertEquals(initial.revision() + 1L, reloaded.revision());
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
        PanelFollowTransformTest.class.getClassLoader(),
        new Class<?>[]{World.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getUID" -> WORLD_UUID;
          case "getKey" -> new NamespacedKey("example", "world");
          case "getName" -> "world";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
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
