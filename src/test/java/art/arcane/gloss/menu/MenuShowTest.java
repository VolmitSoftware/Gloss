package art.arcane.gloss.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.config.components.ComponentData;
import art.arcane.gloss.config.components.HoverEasing;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.enums.MenuComponentType;
import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.gloss.menu.action.NavigationResult;
import art.arcane.gloss.menu.components.ClickableComponent;
import art.arcane.gloss.menu.components.MenuComponent;
import art.arcane.gloss.menu.icon.MenuIcon;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.gloss.util.common.math.CollisionPlane;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class MenuShowTest {
  private final AtomicLong time = new AtomicLong(14000L);
  private final AtomicReference<String> worldName = new AtomicReference<>("world");
  private Gloss previousPlugin;

  @Before
  public void installConditionRuntime() throws ReflectiveOperationException {
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
  public void restoreConditionRuntime() {
    Gloss.instance = previousPlugin;
  }

  @Test
  public void menuReappearsWhenWorldAndTimeMatchWithoutReplacingItsComponents() {
    MenuSession session = session(ShowCondition.of("{{world.name == 'world' && world.time < 12000}}"),
        ShowCondition.ALWAYS);
    ProbeComponent component = (ProbeComponent) session.getComponents().getFirst();

    session.open();
    assertFalse(session.isShown());
    assertFalse(session.isFreezePlayer());
    assertNull(component.icon);

    time.set(6000L);
    session.tick();
    assertTrue(session.isShown());
    assertTrue(session.isFreezePlayer());
    ProbeIcon icon = component.icon;
    assertEquals(1, icon.spawns);
    assertTrue(component.isInteractable());
    assertTrue(component.intersectionDistance(new Vector(0D, 64D, -5D), new Vector(0D, 0D, 1D)).isPresent());

    worldName.set("dungeon");
    assertFalse(component.isInteractable());
    assertTrue(component.intersectionDistance(new Vector(0D, 64D, -5D), new Vector(0D, 0D, 1D)).isEmpty());
    session.tick();
    assertFalse(component.isOpen());
    assertNull(component.particlePlane());
    assertEquals(1, icon.removals);
    session.tick();
    assertEquals(1, icon.removals);

    worldName.set("world");
    session.tick();
    assertSame(icon, component.icon);
    assertEquals(2, icon.spawns);
    assertTrue(component.isInteractable());

    session.close();
    session.tick();
    assertFalse(session.isShown());
    assertFalse(component.isInteractable());
    assertEquals(2, icon.spawns);
  }

  @Test
  public void componentConditionsReevaluateWhileTheMenuRemainsVisible() {
    MenuSession session = session(ShowCondition.ALWAYS, ShowCondition.of("world.time < 12000"));
    ProbeComponent component = (ProbeComponent) session.getComponents().getFirst();

    session.open();
    assertTrue(session.isShown());
    assertFalse(component.isOpen());
    time.set(1000L);
    session.tick();
    assertTrue(component.isInteractable());

    time.set(18000L);
    assertFalse(component.isInteractable());
    session.tick();
    assertTrue(session.isShown());
    assertFalse(component.isOpen());

    time.set(0L);
    session.tick();
    assertTrue(component.isOpen());
    assertEquals(2, component.icon.spawns);
    session.close();
  }

  @Test
  public void parentShowIsAnAdditionalGateAndKeepsTheCurrentIcon() {
    MenuSession session = session(ShowCondition.ALWAYS, ShowCondition.ALWAYS);
    ProbeComponent component = (ProbeComponent) session.getComponents().getFirst();
    session.open();
    ProbeIcon icon = component.icon;

    session.setParentShow(ShowCondition.of("false"));
    assertFalse(component.isInteractable());
    session.tick();
    assertFalse(component.isOpen());
    assertEquals(1, icon.removals);

    session.setParentShow(ShowCondition.ALWAYS);
    session.tick();
    assertTrue(component.isOpen());
    assertSame(icon, component.icon);
    assertEquals(2, icon.spawns);
    session.close();

    MenuSession hiddenMenu = session(ShowCondition.of("false"), ShowCondition.ALWAYS);
    hiddenMenu.setParentShow(ShowCondition.ALWAYS);
    hiddenMenu.open();
    hiddenMenu.tick();
    assertFalse(hiddenMenu.isShown());
    assertFalse(hiddenMenu.getComponents().getFirst().isOpen());
    hiddenMenu.close();
  }

  private MenuSession session(ShowCondition show, ShowCondition componentShow) {
    World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getName" -> worldName.get();
          case "getTime" -> time.get();
          default -> throw new UnsupportedOperationException(method.getName());
        });
    Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getLocation", "getEyeLocation" -> new Location(world, 0D, 64D, 0D);
          case "getWorld" -> world;
          case "getName" -> "viewer";
          default -> throw new UnsupportedOperationException(method.getName());
        });
    MenuDefinitionData definition = new MenuDefinitionData(new Vector(), true, false, 8D,
        false, false, List.of(new MenuComponentData("probe", new Vector(), new ProbeData(), componentShow)),
        List.of(), show);
    definition.setId("show-test");
    MenuTransform transform = new MenuTransform(player.getLocation(), new Vector(), 0F, 0F, 0F, 1F);
    return new MenuSession(definition, player,
        MenuSessionOptions.positioned(transform, request -> NavigationResult.DENIED, 1F));
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
      return new CollisionPlane(anchor.toVector(), 2F, 2F);
    }
  }
}
