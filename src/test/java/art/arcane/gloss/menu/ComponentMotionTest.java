package art.arcane.gloss.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.config.ComponentMotion;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.config.components.ComponentData;
import art.arcane.gloss.config.components.HoverEasing;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.config.menu.MenuDocumentParser;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.enums.MenuComponentType;
import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.gloss.menu.action.NavigationResult;
import art.arcane.gloss.menu.components.ClickableComponent;
import art.arcane.gloss.menu.components.MenuComponent;
import art.arcane.gloss.menu.icon.MenuIcon;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.gloss.util.common.math.CollisionPlane;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ComponentMotionTest {
  private static final Vector EYE = new Vector(0D, 0D, 1D);
  private Gloss previousPlugin;

  @Before
  public void installRuntime() throws ReflectiveOperationException {
    previousPlugin = Gloss.instance;
    Field allocatorField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
    allocatorField.setAccessible(true);
    Object allocator = allocatorField.get(null);
    Gloss plugin = (Gloss) allocator.getClass().getMethod("allocateInstance", Class.class)
        .invoke(allocator, Gloss.class);
    Field text = Gloss.class.getDeclaredField("text");
    text.setAccessible(true);
    text.set(plugin, new TextPipeline(plugin));
    Gloss.instance = plugin;
  }

  @After
  public void restoreRuntime() {
    Gloss.instance = previousPlugin;
  }

  @Test
  public void movesTheExistingIconAndItsHitboxWithoutRespawning() {
    MenuSession session = session(ComponentMotion.of("session.pos", null, null, null, "3"), ShowCondition.ALWAYS, 2F);
    session.variables().set("pos", 0D);
    session.open();
    ProbeComponent component = (ProbeComponent) session.getComponents().getFirst();
    Vector start = component.getLocation().toVector();
    assertTrue(component.intersectionDistance(behind(start), EYE).isPresent());

    session.variables().set("pos", 10D);
    session.tick();

    Vector moved = session.getTransform().componentPosition(new Vector(10D, 0D, 0D)).toVector();
    assertEquals(0D, component.getLocation().toVector().distance(moved), 1.0E-9D);
    assertEquals(20D, moved.distance(start), 1.0E-9D);
    assertEquals(1, component.icon.spawns);
    assertEquals(0, component.icon.removals);
    assertEquals(3, component.icon.ticks());
    assertTrue(component.intersectionDistance(behind(moved), EYE).isPresent());
    assertTrue(component.intersectionDistance(behind(start), EYE).isEmpty());
  }

  @Test
  public void scaleResizesTheIconGeometryAndHitbox() {
    MenuSession session = session(ComponentMotion.of(null, null, null, "session.size", "2"), ShowCondition.ALWAYS, 2F);
    session.variables().set("size", 1D);
    session.open();
    ProbeComponent component = (ProbeComponent) session.getComponents().getFirst();
    assertEquals(2F, component.icon.scale(), 0F);

    session.variables().set("size", 0.5D);
    session.tick();

    assertEquals(1F, component.icon.scale(), 0F);
    assertEquals(1, component.icon.spawns);
    Vector center = component.getLocation().toVector();
    assertTrue(component.intersectionDistance(behind(center.clone().add(new Vector(0.9D, 0D, 0D))), EYE).isPresent());
    assertTrue(component.intersectionDistance(behind(center.clone().add(new Vector(1.5D, 0D, 0D))), EYE).isEmpty());
  }

  @Test
  public void zeroScaleKeepsTheMenuTransformValid() {
    MenuSession session = session(ComponentMotion.of(null, null, null, "0", "2"), ShowCondition.ALWAYS, 1F);
    session.open();
    ProbeComponent component = (ProbeComponent) session.getComponents().getFirst();

    assertEquals(ComponentMotion.MIN_SCALE, component.icon.scale(), 0F);
  }

  @Test
  public void clockExpressionsAdvanceEveryTick() throws InterruptedException {
    MenuSession session = session(ComponentMotion.of("time.ms", null, null, null, "1"), ShowCondition.ALWAYS, 1F);
    session.open();
    ProbeComponent component = (ProbeComponent) session.getComponents().getFirst();
    Vector opened = component.getLocation().toVector();

    Thread.sleep(5L);
    session.tick();

    assertTrue(component.getLocation().toVector().distance(opened) > 0D);
  }

  @Test
  public void reopensAtTheCurrentMotionPosition() {
    MenuSession session = session(ComponentMotion.of("session.pos", null, null, null, "3"),
        ShowCondition.of("session.on == 1"), 1F);
    session.variables().set("on", 1D);
    session.variables().set("pos", 0D);
    session.open();
    ProbeComponent component = (ProbeComponent) session.getComponents().getFirst();
    assertTrue(component.isOpen());

    session.variables().set("on", 0D);
    session.tick();
    assertFalse(component.isOpen());
    session.variables().set("pos", 4D);
    session.variables().set("on", 1D);
    session.tick();

    assertTrue(component.isOpen());
    assertEquals(0D, component.getLocation().toVector().distance(
        session.getTransform().componentPosition(new Vector(4D, 0D, 0D)).toVector()), 1.0E-9D);
    assertEquals(2, component.icon.spawns);
  }

  @Test
  public void aFailingExpressionLeavesTheIconInPlaceUntilItRecovers() {
    MenuSession session = session(ComponentMotion.of("session.pos", null, null, null, "3"), ShowCondition.ALWAYS, 1F);
    session.variables().set("pos", 3D);
    session.open();
    ProbeComponent component = (ProbeComponent) session.getComponents().getFirst();
    Vector placed = component.getLocation().toVector();

    Gloss.instance = null;
    session.variables().set("pos", "not a number");
    session.tick();
    assertEquals(0D, component.getLocation().toVector().distance(placed), 0D);

    session.variables().set("pos", 5D);
    session.tick();
    assertEquals(0D, component.getLocation().toVector().distance(
        session.getTransform().componentPosition(new Vector(5D, 0D, 0D)).toVector()), 1.0E-9D);
  }

  @Test
  public void ticksFollowTheirExpressionAndStayInRange() {
    MenuSession session = session(ComponentMotion.of("session.pos", null, null, null, "session.pos > 5 ? 0 : session.ease"),
        ShowCondition.ALWAYS, 1F);
    session.variables().set("pos", 1D);
    session.variables().set("ease", 4D);
    session.open();
    ProbeComponent component = (ProbeComponent) session.getComponents().getFirst();
    assertEquals(4, component.icon.ticks());

    session.variables().set("pos", 6D);
    session.tick();
    assertEquals(0, component.icon.ticks());

    session.variables().set("pos", 1D);
    session.variables().set("ease", 500D);
    session.tick();
    assertEquals(ComponentMotion.MAX_TICKS, component.icon.ticks());
    assertEquals(1, component.icon.spawns);
  }

  @Test
  public void aComponentWithoutMotionIsNeverReevaluated() {
    MenuSession session = session(ComponentMotion.NONE, ShowCondition.ALWAYS, 1F);
    session.open();
    ProbeComponent component = (ProbeComponent) session.getComponents().getFirst();
    Vector placed = component.getLocation().toVector();

    session.tick();

    assertEquals(placed, component.getLocation().toVector());
    assertEquals(0, component.icon.ticks());
  }

  @Test
  public void parsesNumbersExpressionsAndTemplates() {
    ComponentMotion motion = DocumentParsers.GSON.fromJson(
        "{\"x\":1.5,\"y\":\"{{ session.y }}\",\"scale\":\"session.size * 2\",\"ticks\":7,\"note\":\"kept out\"}",
        ComponentMotion.class);

    assertTrue(motion.isActive());
    JsonObject written = DocumentParsers.GSON.toJsonTree(motion).getAsJsonObject();
    assertEquals("1.5", written.get("x").getAsString());
    assertEquals("session.y", written.get("y").getAsString());
    assertEquals("session.size * 2", written.get("scale").getAsString());
    assertEquals("7", written.get("ticks").getAsString());
    assertFalse(written.has("z"));
    assertFalse(written.has("note"));
  }

  @Test
  public void ticksDefaultAndAreBounded() {
    assertEquals(ComponentMotion.DEFAULT_TICKS,
        DocumentParsers.GSON.fromJson("{\"x\":1}", ComponentMotion.class).ticks(null));
    assertEquals(0, DocumentParsers.GSON.fromJson("{\"x\":1,\"ticks\":0}", ComponentMotion.class).ticks(null));
    assertEquals(59, DocumentParsers.GSON.fromJson("{\"x\":1,\"ticks\":59}", ComponentMotion.class).ticks(null));
    assertThrows(IllegalArgumentException.class,
        () -> DocumentParsers.GSON.fromJson("{\"x\":1,\"ticks\":1.5}", ComponentMotion.class));
    assertThrows(IllegalArgumentException.class,
        () -> DocumentParsers.GSON.fromJson("{\"x\":1,\"ticks\":\"60\"}", ComponentMotion.class));
    assertThrows(IllegalArgumentException.class,
        () -> DocumentParsers.GSON.fromJson("{\"x\":1,\"ticks\":60}", ComponentMotion.class));
    assertThrows(IllegalArgumentException.class,
        () -> DocumentParsers.GSON.fromJson("{\"x\":1,\"ticks\":-1}", ComponentMotion.class));
  }

  @Test
  public void aMalformedExpressionIsRefusedWhenTheMenuLoads() {
    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> MenuDocumentParser.parse("motion", document("{\"x\":\"session.pos +\"}")));

    assertTrue(failure.getMessage(), failure.getMessage().contains("motion.x"));
  }

  @Test
  public void menuDocumentsCarryTheMotionBlock() {
    MenuComponentData with = MenuDocumentParser.parse("motion", document("{\"x\":\"session.pos\",\"ticks\":4}"))
        .definition().getComponents().getFirst();
    MenuComponentData without = MenuDocumentParser.parse("still", """
        {"offset":[0,0,0],"components":[{"id":"icon","offset":[1,2,3],
        "data":{"type":"decoration","icon":{"type":"text","text":"x"}}}]}""")
        .definition().getComponents().getFirst();

    assertEquals(4, with.motion().ticks(null));
    assertTrue(with.motion().isActive());
    assertSame(ComponentMotion.NONE, without.motion());
  }

  private static String document(String motion) {
    return """
        {"offset":[0,0,0],"components":[{"id":"icon","offset":[1,2,3],"motion":%s,
        "data":{"type":"decoration","icon":{"type":"text","text":"x"}}}]}""".formatted(motion);
  }

  private static Vector behind(Vector target) {
    return target.clone().subtract(EYE.clone().multiply(5D));
  }

  private static MenuSession session(ComponentMotion motion, ShowCondition show, float scale) {
    World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getName" -> "world";
          case "getTime" -> 6000L;
          default -> throw new UnsupportedOperationException(method.getName());
        });
    Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getLocation", "getEyeLocation" -> new Location(world, 0D, 64D, 0D);
          case "getWorld" -> world;
          case "getName" -> "viewer";
          default -> throw new UnsupportedOperationException(method.getName());
        });
    MenuDefinitionData definition = new MenuDefinitionData(new Vector(), true, false, 8D, false, false,
        List.of(new MenuComponentData("probe", new Vector(), new ProbeData(), show, motion)),
        List.of(), ShowCondition.ALWAYS, Map.of(), List.of());
    definition.setId("motion-test");
    MenuTransform transform = new MenuTransform(player.getLocation(), new Vector(), 0F, 0F, 0F, scale);
    return new MenuSession(definition, player,
        MenuSessionOptions.positioned(transform, request -> NavigationResult.DENIED, scale));
  }

  private record ProbeData() implements ComponentData {
    @Override
    public MenuComponentType getType() {
      return MenuComponentType.BUTTON;
    }

    @Override
    public MenuComponent<?> createComponent(MenuSession session, MenuComponentData data) {
      return new ProbeComponent(session, data);
    }
  }

  private static final class ProbeComponent extends ClickableComponent<ProbeData> {
    private ProbeIcon icon;

    private ProbeComponent(MenuSession session, MenuComponentData data) {
      super(session, data, 0F, null, 0, HoverEasing.LINEAR);
    }

    @Override
    public void onClick(HoloClickTrigger trigger) {
    }

    @Override
    protected MenuIcon<?> createIcon() {
      try {
        icon = new ProbeIcon(session, location);
        return icon;
      } catch (MenuIconException failure) {
        throw new AssertionError(failure);
      }
    }
  }

  private static final class ProbeIcon extends MenuIcon<MenuIconData> {
    private int spawns;
    private int removals;

    private ProbeIcon(MenuSession session, Location location) throws MenuIconException {
      super(session, location, null);
    }

    private float scale() {
      return uiScale();
    }

    private int ticks() {
      return motionTicks();
    }

    @Override
    public void spawn() {
      spawns++;
    }

    @Override
    public void remove() {
      removals++;
    }

    @Override
    protected List<UUID> createDisplayEntities(Location location) {
      return List.of();
    }

    @Override
    public CollisionPlane createBoundingBox(Location anchor) {
      return new CollisionPlane(anchor.toVector(), 2F * uiScale(), 2F * uiScale());
    }
  }
}
