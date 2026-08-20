package art.arcane.gloss.panel;

import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.config.components.ComponentData;
import art.arcane.gloss.enums.MenuComponentType;
import art.arcane.gloss.enums.NavigationMode;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.components.MenuComponent;
import art.arcane.gloss.menu.icon.MenuIcon;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PanelViewSessionTest {
  private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000401");
  private static final World WORLD = world();

  private static Object previousServer;

  @BeforeClass
  public static void installServer() throws ReflectiveOperationException {
    Field server = serverField();
    previousServer = server.get(null);
    server.set(null, server());
  }

  @AfterClass
  public static void restoreServer() throws ReflectiveOperationException {
    serverField().set(null, previousServer);
  }

  @Test
  public void failedRootReplacementClosesTheOldViewWithoutCommittingNewState() {
    PanelDefinition initial = PanelDefinition.create("board", "root", transform(1D));
    PanelDefinition replacement = initial.withRootMenu("broken").withTransform(transform(8D));
    AtomicInteger rootCloseCalls = new AtomicInteger();
    AtomicInteger brokenCloseCalls = new AtomicInteger();
    MenuDefinitionData root = menu("root", new TrackingData(rootCloseCalls, false));
    MenuDefinitionData broken = menu("broken", new TrackingData(brokenCloseCalls, true));
    PanelViewSession view = new PanelViewSession(
        initial,
        initial.transform(),
        player(),
        menuId -> switch (menuId) {
          case "root" -> root;
          case "broken" -> broken;
          default -> null;
        },
        ignored -> {
        }
    );

    assertEquals(NavigationResult.APPLIED, view.open());
    MenuSession originalSession = view.session();
    assertFalse(view.closed());
    assertEquals("root", view.currentMenuId());

    assertEquals(NavigationResult.DENIED, view.update(replacement, replacement.transform()));

    assertTrue(view.closed());
    assertNull(view.session());
    assertNull(view.currentMenuId());
    assertSame(initial, view.definition());
    assertEquals(initial.transform(), view.effectiveTransform());
    assertEquals(1, rootCloseCalls.get());
    assertEquals(1, brokenCloseCalls.get());
    assertFalse(originalSession.getComponents().isEmpty());
  }

  @Test
  public void pushingBuildsAStackAndBackUnwindsItUntilNothingIsLeft() {
    PanelViewSession view = view(new AtomicInteger());

    assertEquals(NavigationResult.APPLIED, view.open());
    assertEquals("root", view.currentMenuId());

    assertEquals(NavigationResult.APPLIED, view.navigate(push("alpha")));
    assertEquals(NavigationResult.APPLIED, view.navigate(push("beta")));
    assertEquals("beta", view.currentMenuId());

    assertEquals(NavigationResult.APPLIED, view.navigate(request(NavigationMode.BACK)));
    assertEquals("alpha", view.currentMenuId());
    assertEquals(NavigationResult.APPLIED, view.navigate(request(NavigationMode.BACK)));
    assertEquals("root", view.currentMenuId());

    assertEquals("an exhausted history must report no history, not close the view",
        NavigationResult.NO_HISTORY, view.navigate(request(NavigationMode.BACK)));
    assertEquals("root", view.currentMenuId());
    assertFalse(view.closed());
  }

  @Test
  public void replaceSwapsTheMenuWithoutTouchingTheHistory() {
    PanelViewSession view = view(new AtomicInteger());
    view.open();
    view.navigate(push("alpha"));

    assertEquals(NavigationResult.APPLIED, view.navigate(new NavigationRequest(NavigationMode.REPLACE, "beta")));
    assertEquals("beta", view.currentMenuId());

    assertEquals(NavigationResult.APPLIED, view.navigate(request(NavigationMode.BACK)));
    assertEquals("a replace must not have recorded a history entry", "root", view.currentMenuId());
    assertEquals(NavigationResult.NO_HISTORY, view.navigate(request(NavigationMode.BACK)));
  }

  @Test
  public void homeReturnsToTheRootMenuAndDropsTheWholeHistory() {
    PanelViewSession view = view(new AtomicInteger());
    view.open();
    view.navigate(push("alpha"));
    view.navigate(push("beta"));

    assertEquals(NavigationResult.APPLIED, view.navigate(request(NavigationMode.HOME)));
    assertEquals("root", view.currentMenuId());
    assertEquals(NavigationResult.NO_HISTORY, view.navigate(request(NavigationMode.BACK)));
  }

  @Test
  public void homeIsAlwaysAvailableEvenStandingOnTheRoot() {
    PanelViewSession view = view(new AtomicInteger());
    view.open();

    assertEquals(NavigationResult.APPLIED, view.navigate(request(NavigationMode.HOME)));
    assertEquals("root", view.currentMenuId());
  }

  @Test
  public void pushingTheSameMenuTwiceRecordsBothEntries() {
    PanelViewSession view = view(new AtomicInteger());
    view.open();
    view.navigate(push("alpha"));

    assertEquals(NavigationResult.APPLIED, view.navigate(push("alpha")));

    assertEquals(NavigationResult.APPLIED, view.navigate(request(NavigationMode.BACK)));
    assertEquals("a repeat push is recorded, not collapsed", "alpha", view.currentMenuId());
    assertEquals(NavigationResult.APPLIED, view.navigate(request(NavigationMode.BACK)));
    assertEquals("root", view.currentMenuId());
  }

  @Test
  public void closeAsksTheOwnerToTearTheViewDownAndLeavesTheMenuStanding() {
    AtomicInteger closeRequests = new AtomicInteger();
    PanelViewSession view = view(closeRequests);
    view.open();
    view.navigate(push("alpha"));

    assertEquals(NavigationResult.APPLIED, view.navigate(request(NavigationMode.CLOSE)));

    assertEquals(1, closeRequests.get());
    assertEquals("the view itself is torn down by its owner, not by the navigation",
        "alpha", view.currentMenuId());
    assertFalse(view.closed());
  }

  @Test
  public void returnHomeIsRefusedOnTheRootAndClearsTheHistoryElsewhere() {
    PanelViewSession view = view(new AtomicInteger());
    view.open();

    assertFalse("returning home from the root is a no-op", view.returnHome());

    view.navigate(push("alpha"));
    assertTrue(view.returnHome());
    assertEquals("root", view.currentMenuId());
    assertEquals(NavigationResult.NO_HISTORY, view.navigate(request(NavigationMode.BACK)));
  }

  @Test
  public void reloadingTheCurrentMenuKeepsTheHistory() {
    PanelViewSession view = view(new AtomicInteger());
    view.open();
    view.navigate(push("alpha"));

    assertTrue(view.reloadCurrent());
    assertEquals("alpha", view.currentMenuId());

    assertEquals(NavigationResult.APPLIED, view.navigate(request(NavigationMode.BACK)));
    assertEquals("root", view.currentMenuId());
  }

  @Test
  public void adoptingANewRootMenuDropsTheHistoryBuiltUnderTheOldOne() {
    PanelDefinition initial = PanelDefinition.create("board", "root", transform(1D));
    PanelViewSession view = view(initial, new AtomicInteger());
    view.open();
    view.navigate(push("alpha"));

    PanelDefinition replacement = initial.withRootMenu("beta");
    assertEquals(NavigationResult.APPLIED, view.update(replacement, replacement.transform()));
    assertEquals("beta", view.currentMenuId());

    assertEquals(NavigationResult.NO_HISTORY, view.navigate(request(NavigationMode.BACK)));
  }

  @Test
  public void closingTheViewForgetsTheHistoryAndDeniesFurtherNavigation() {
    PanelViewSession view = view(new AtomicInteger());
    view.open();
    view.navigate(push("alpha"));

    view.close();

    assertTrue(view.closed());
    assertNull(view.currentMenuId());
    assertEquals(NavigationResult.DENIED, view.navigate(request(NavigationMode.BACK)));
  }

  private static NavigationRequest push(String target) {
    return new NavigationRequest(NavigationMode.PUSH, target);
  }

  private static NavigationRequest request(NavigationMode mode) {
    return new NavigationRequest(mode, null);
  }

  private static PanelViewSession view(AtomicInteger closeRequests) {
    return view(PanelDefinition.create("board", "root", transform(1D)), closeRequests);
  }

  private static PanelViewSession view(PanelDefinition definition, AtomicInteger closeRequests) {
    Map<String, MenuDefinitionData> menus = Map.of(
        "root", menu("root"),
        "alpha", menu("alpha"),
        "beta", menu("beta")
    );
    return new PanelViewSession(
        definition,
        definition.transform(),
        player(),
        menus::get,
        ignored -> closeRequests.incrementAndGet()
    );
  }

  private static MenuDefinitionData menu(String id) {
    MenuDefinitionData menu = new MenuDefinitionData(
        new Vector(), false, false, 8D, false, false, List.<MenuComponentData>of());
    menu.setId(id);
    return menu;
  }

  private static PanelTransform transform(double x) {
    return new PanelTransform("example:world", WORLD_UUID, x, 64D, 3D, 0D, 0D, 0D, 1D);
  }

  private static MenuDefinitionData menu(String id, ComponentData componentData) {
    MenuDefinitionData menu = new MenuDefinitionData(
        new Vector(),
        false,
        false,
        8D,
        false,
        false,
        List.of(new MenuComponentData("probe", new Vector(), componentData))
    );
    menu.setId(id);
    return menu;
  }

  private record TrackingData(AtomicInteger closeCalls, boolean failOpen) implements ComponentData {
    @Override
    public MenuComponentType getType() {
      return MenuComponentType.DECO;
    }

    @Override
    public MenuComponent<?> createComponent(MenuSession session, MenuComponentData data) {
      return new TrackingComponent(session, data);
    }
  }

  private static final class TrackingComponent extends MenuComponent<TrackingData> {
    private TrackingComponent(MenuSession session, MenuComponentData data) {
      super(session, data);
    }

    @Override
    public void open() {
      if (data.failOpen()) {
        throw new IllegalStateException("root replacement failed");
      }
    }

    @Override
    public void close() {
      data.closeCalls().incrementAndGet();
    }

    @Override
    protected void onTick(Vector eyeOrigin, Vector eyeDirection) {
    }

    @Override
    protected MenuIcon<?> createIcon() {
      return null;
    }

    @Override
    protected void onOpen() {
    }

    @Override
    protected void onClose() {
    }
  }

  private static Player player() {
    return (Player) Proxy.newProxyInstance(
        PanelViewSessionTest.class.getClassLoader(),
        new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getName" -> "viewer";
          case "hasPermission" -> true;
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "viewer";
          default -> throw new UnsupportedOperationException(method.getName());
        }
    );
  }

  private static Server server() {
    return (Server) Proxy.newProxyInstance(
        PanelViewSessionTest.class.getClassLoader(),
        new Class<?>[]{Server.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getWorld" -> Arrays.stream(method.getParameterTypes()).anyMatch(UUID.class::equals)
              && args[0].equals(WORLD_UUID) ? WORLD : null;
          case "getLogger" -> Logger.getAnonymousLogger();
          case "getName", "getVersion", "getBukkitVersion" -> "test";
          case "isPrimaryThread" -> true;
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "server";
          default -> throw new UnsupportedOperationException(method.getName());
        }
    );
  }

  private static World world() {
    return (World) Proxy.newProxyInstance(
        PanelViewSessionTest.class.getClassLoader(),
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

  private static Field serverField() throws ReflectiveOperationException {
    Field field = org.bukkit.Bukkit.class.getDeclaredField("server");
    field.setAccessible(true);
    return field;
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
