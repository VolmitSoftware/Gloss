package art.arcane.gloss.menu.icon;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.config.components.ComponentData;
import art.arcane.gloss.config.components.HoverEasing;
import art.arcane.gloss.config.icon.IconBillboard;
import art.arcane.gloss.enums.MenuComponentType;
import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.MenuSessionOptions;
import art.arcane.gloss.menu.components.ClickableComponent;
import art.arcane.gloss.menu.components.MenuComponent;
import art.arcane.gloss.util.common.math.CollisionPlane;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Characterization pins for hitbox orientation (plan item T19/C3): the oriented plane is a pure
 * function of the viewer's eye position. Orienting twice from the same eye is a state no-op, and
 * (for the world-up modes) leaving and returning to an eye position restores the exact original
 * orientation. This is the precondition that lets a lane orient the hitbox once per tick and
 * reuse it — including collapsing the double {@code orientHitbox} in the click path.
 *
 * <p>{@code HORIZONTAL} is deliberately pinned only for same-eye idempotence: its next orientation
 * reuses the plane's current right axis, so it is path-dependent by design and a return-to-pose
 * pin would overconstrain it.
 */
public class CharacterizationHitboxOrientationTest {

  private static final Vector CENTER = new Vector(10.0D, 65.0D, 10.0D);
  private static final Vector EYE_A = new Vector(7.0D, 66.0D, 4.0D);
  private static final Vector EYE_B = new Vector(14.5D, 63.0D, 12.0D);

  @Test
  public void fixedBillboardNeverReorients() {
    CollisionPlane plane = new CollisionPlane(CENTER.clone(), 2.0F, 2.0F);
    Orientation before = Orientation.of(plane);

    MenuIcon.orientBillboardPlane(plane, IconBillboard.FIXED, EYE_A.clone());

    Orientation.of(plane).assertExactly("FIXED must never touch the plane", before);
  }

  @Test
  public void centerOrientationIsAPureFunctionOfTheEye() {
    CollisionPlane plane = new CollisionPlane(CENTER.clone(), 2.0F, 2.0F);

    MenuIcon.orientBillboardPlane(plane, IconBillboard.CENTER, EYE_A.clone());
    Orientation first = Orientation.of(plane);
    MenuIcon.orientBillboardPlane(plane, IconBillboard.CENTER, EYE_A.clone());
    Orientation.of(plane).assertExactly("re-orienting from the same eye must be a no-op", first);

    MenuIcon.orientBillboardPlane(plane, IconBillboard.CENTER, EYE_B.clone());
    MenuIcon.orientBillboardPlane(plane, IconBillboard.CENTER, EYE_A.clone());
    Orientation.of(plane).assertExactly(
        "returning to an eye position must restore the exact original orientation", first);
  }

  @Test
  public void verticalOrientationIsAPureFunctionOfTheEye() {
    CollisionPlane plane = new CollisionPlane(CENTER.clone(), 2.0F, 2.0F);

    MenuIcon.orientBillboardPlane(plane, IconBillboard.VERTICAL, EYE_A.clone());
    Orientation first = Orientation.of(plane);
    assertEquals("VERTICAL keeps world-up", new Vector(0, 1, 0), plane.getUp());

    MenuIcon.orientBillboardPlane(plane, IconBillboard.VERTICAL, EYE_B.clone());
    MenuIcon.orientBillboardPlane(plane, IconBillboard.VERTICAL, EYE_A.clone());
    Orientation.of(plane).assertExactly(
        "returning to an eye position must restore the exact original orientation", first);
  }

  @Test
  public void horizontalOrientationIsIdempotentAtTheSameEye() {
    CollisionPlane plane = new CollisionPlane(CENTER.clone(), 2.0F, 2.0F);

    MenuIcon.orientBillboardPlane(plane, IconBillboard.HORIZONTAL, EYE_A.clone());
    Orientation first = Orientation.of(plane);
    MenuIcon.orientBillboardPlane(plane, IconBillboard.HORIZONTAL, EYE_A.clone());

    Orientation.of(plane).assertClose(
        "re-orienting HORIZONTAL from the same eye must not drift the plane", first);
  }

  @Test
  public void componentIntersectionDistanceIsAPureFunctionOfTheEyePose() {
    AtomicReference<Location> playerAt = new AtomicReference<>(new Location(null, 0.0D, 65.0D, 0.0D));
    MenuDefinitionData data = new MenuDefinitionData(new Vector(0.0D, 0.0D, 2.0D), false, false,
        8.0D, false, false,
        List.of(new MenuComponentData("probe", new Vector(), new ProbeClickableData())));
    data.setId("hitbox-probe");
    Player player = player(playerAt);
    MenuSession session = new MenuSession(data, player, MenuSessionOptions.personal(data, player, null));
    session.open();
    ClickableComponent<?> clickable = (ClickableComponent<?>) session.getComponents().get(0);
    Vector planeCenter = clickable.getLocation().toVector();

    Vector originA = planeCenter.clone().subtract(new Vector(0.0D, 0.0D, 5.0D));
    Vector towardPlane = new Vector(0.0D, 0.0D, 1.0D);
    OptionalDouble first = clickable.intersectionDistance(originA.clone(), towardPlane.clone());
    OptionalDouble repeat = clickable.intersectionDistance(originA.clone(), towardPlane.clone());

    assertTrue(first.isPresent());
    assertEquals("the same eye pose must always yield the same distance",
        first.getAsDouble(), repeat.getAsDouble(), 0.0D);
    assertEquals(5.0D, first.getAsDouble(), 1.0E-9D);

    Vector originB = planeCenter.clone().subtract(new Vector(0.0D, 0.0D, 8.0D));
    OptionalDouble farther = clickable.intersectionDistance(originB.clone(), towardPlane.clone());
    assertEquals(8.0D, farther.getAsDouble(), 1.0E-9D);

    OptionalDouble back = clickable.intersectionDistance(originA.clone(), towardPlane.clone());
    assertEquals("leaving and returning to a pose must reproduce the original distance",
        first.getAsDouble(), back.getAsDouble(), 0.0D);
  }

  // ---------------------------------------------------------------------
  // Plumbing
  // ---------------------------------------------------------------------

  private record Orientation(Vector up, Vector right, Vector normal, Vector center) {
    private static Orientation of(CollisionPlane plane) {
      return new Orientation(plane.getUp().clone(), plane.getRight().clone(),
          plane.getNormal().clone(), plane.getCenter().clone());
    }

    private void assertExactly(String message, Orientation expected) {
      assertComponent(message + " [up]", expected.up, up, 0.0D);
      assertComponent(message + " [right]", expected.right, right, 0.0D);
      assertComponent(message + " [normal]", expected.normal, normal, 0.0D);
      assertComponent(message + " [center]", expected.center, center, 0.0D);
    }

    private void assertClose(String message, Orientation expected) {
      assertComponent(message + " [up]", expected.up, up, 1.0E-12D);
      assertComponent(message + " [right]", expected.right, right, 1.0E-12D);
      assertComponent(message + " [normal]", expected.normal, normal, 1.0E-12D);
      assertComponent(message + " [center]", expected.center, center, 0.0D);
    }

    private static void assertComponent(String message, Vector expected, Vector actual, double delta) {
      assertEquals(message + ".x", expected.getX(), actual.getX(), delta);
      assertEquals(message + ".y", expected.getY(), actual.getY(), delta);
      assertEquals(message + ".z", expected.getZ(), actual.getZ(), delta);
    }
  }

  private record ProbeClickableData() implements ComponentData {
    @Override
    public MenuComponentType getType() {
      return MenuComponentType.BUTTON;
    }

    @Override
    public MenuComponent<?> createComponent(MenuSession session, MenuComponentData data) {
      return new ProbeClickable(session, data);
    }
  }

  private static final class ProbeClickable extends ClickableComponent<ProbeClickableData> {
    private ProbeClickable(MenuSession session, MenuComponentData data) {
      super(session, data, 0.05F, null, 4, HoverEasing.EASE_OUT_CUBIC);
    }

    @Override
    public void onClick(HoloClickTrigger trigger) {
    }

    @Override
    protected MenuIcon<?> createIcon() {
      try {
        return new ProbeIcon(session, location);
      } catch (MenuIconException impossible) {
        throw new AssertionError(impossible);
      }
    }
  }

  /** A packet-free icon with a fixed 2x2 plane centered on its anchor. */
  private static final class ProbeIcon extends MenuIcon<art.arcane.gloss.config.icon.MenuIconData> {
    private ProbeIcon(MenuSession session, Location loc) throws MenuIconException {
      super(session, loc, null);
    }

    @Override
    protected List<UUID> createDisplayEntities(Location loc) {
      return List.of();
    }

    @Override
    public CollisionPlane createBoundingBox(Location anchor) {
      return new CollisionPlane(anchor.toVector(), 2.0F, 2.0F);
    }
  }

  private static Player player(AtomicReference<Location> location) {
    return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getLocation" -> location.get().clone();
          case "getEyeLocation" -> location.get().clone().add(0.0D, 1.62D, 0.0D);
          case "getName" -> "orienter";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "Player[orienter]";
          default -> throw new UnsupportedOperationException(
              "hitbox orientation touched Player#" + method.getName());
        });
  }
}
