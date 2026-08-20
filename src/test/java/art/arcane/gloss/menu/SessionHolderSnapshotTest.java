package art.arcane.gloss.menu;

import art.arcane.gloss.api.HoloCloseReason;
import art.arcane.gloss.api.HoloMenuState;
import art.arcane.gloss.api.internal.ApiMenuHandle;
import art.arcane.gloss.api.internal.ApiOwner;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.config.components.ComponentData;
import art.arcane.gloss.enums.MenuComponentType;
import art.arcane.gloss.enums.NavigationMode;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import art.arcane.gloss.menu.components.MenuComponent;
import art.arcane.gloss.menu.icon.MenuIcon;
import art.arcane.gloss.service.GlossPlaceholderExpansion;
import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.volmlib.util.bukkit.papi.PlaceholderValues;
import art.arcane.volmlib.util.bukkit.papi.PlayerSnapshotStore;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SessionHolderSnapshotTest {
  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
  private static final UUID IMPOSTOR = UUID.fromString("00000000-0000-0000-0000-0000000000c4");

  @Test
  public void holderCapturesThePlayerIdOnceAndPublishesUnderThatCapturedId() {
    AtomicReference<UUID> identity = new AtomicReference<>(PLAYER);
    AtomicInteger idReads = new AtomicInteger();
    PlayerSnapshotStore<String> openMenus = new PlayerSnapshotStore<>();
    SessionHolder holder = new SessionHolder(player(identity, idReads, new AtomicBoolean(true)), openMenus);

    assertEquals("the holder must read the player id exactly once, at construction", 1, idReads.get());
    assertNull("constructing a holder must not publish a menu", openMenus.get(PLAYER));

    identity.set(IMPOSTOR);
    holder.openSession(menu("alpha"), null);

    assertEquals("the publish must key on the id captured at construction", "alpha", openMenus.get(PLAYER));
    assertNull("a lazily re-read player id would leak the snapshot onto another key", openMenus.get(IMPOSTOR));
    assertEquals("opening a session must not re-read the player id", 1, idReads.get());
  }

  @Test
  public void openingASessionPublishesTheOpenMenuIdIntoTheStore() {
    PlayerSnapshotStore<String> openMenus = new PlayerSnapshotStore<>();
    SessionHolder holder = holder(openMenus);

    holder.openSession(menu("alpha"), null);

    assertTrue(holder.hasSession());
    assertEquals("alpha", openMenus.get(PLAYER));
  }

  @Test
  public void movingRequiresAnOpenSession() {
    SessionHolder holder = holder(new PlayerSnapshotStore<>());

    assertFalse(holder.moveSession(new Location(null, 10D, 70D, 20D)));
  }

  @Test
  public void movingReanchorsTheOpenSessionWithoutReplacingIt() {
    SessionHolder holder = holder(new PlayerSnapshotStore<>());
    holder.openSession(menu("alpha", new Vector(2D, 1.5D, 3D)), null);
    AtomicReference<MenuSession> before = new AtomicReference<>();
    holder.onSession(before::set);
    Location anchor = new Location(null, 10D, 70D, 20D);

    assertTrue(holder.moveSession(anchor));

    AtomicReference<MenuSession> after = new AtomicReference<>();
    holder.onSession(after::set);
    assertSame(before.get(), after.get());
    assertEquals(8D, after.get().getCenterPoint().getX(), 0D);
    assertEquals(71.5D, after.get().getCenterPoint().getY(), 0D);
    assertEquals(23D, after.get().getCenterPoint().getZ(), 0D);
    assertEquals(10D, anchor.getX(), 0D);
    assertEquals(70D, anchor.getY(), 0D);
    assertEquals(20D, anchor.getZ(), 0D);
  }

  @Test
  public void openingOverAnOpenSessionRepublishesTheReplacingMenuId() {
    PlayerSnapshotStore<String> openMenus = new PlayerSnapshotStore<>();
    SessionHolder holder = holder(openMenus);

    holder.openSession(menu("alpha"), null);
    holder.openSession(menu("beta"), null);

    assertEquals("beta", openMenus.get(PLAYER));
  }

  @Test
  public void navigationPushesARealStackAndBackPopsWithoutToggling() {
    PlayerSnapshotStore<String> openMenus = new PlayerSnapshotStore<>();
    SessionHolder holder = holder(openMenus);
    holder.openSession(menu("root"), null);

    assertEquals(NavigationResult.APPLIED, holder.navigateSession(
        menu("category"), new NavigationRequest(NavigationMode.PUSH, "category")));
    assertEquals(NavigationResult.APPLIED, holder.navigateSession(
        menu("detail"), new NavigationRequest(NavigationMode.PUSH, "detail")));
    assertEquals("category", holder.lastSessionId());
    assertEquals("root", holder.rootSessionId());

    assertEquals(NavigationResult.APPLIED, holder.navigateSession(
        menu("category"), new NavigationRequest(NavigationMode.BACK, null)));
    assertEquals("root", holder.lastSessionId());
    assertEquals("category", openMenus.get(PLAYER));

    assertEquals(NavigationResult.APPLIED, holder.navigateSession(
        menu("root"), new NavigationRequest(NavigationMode.BACK, null)));
    assertNull(holder.lastSessionId());
    assertEquals("root", openMenus.get(PLAYER));
    assertEquals(NavigationResult.NO_HISTORY, holder.navigateSession(
        menu("detail"), new NavigationRequest(NavigationMode.BACK, null)));
  }

  @Test
  public void replaceKeepsHistoryAndHomeClearsIt() {
    SessionHolder holder = holder(new PlayerSnapshotStore<>());
    holder.openSession(menu("root"), null);
    holder.navigateSession(menu("category"), new NavigationRequest(NavigationMode.PUSH, "category"));

    assertEquals(NavigationResult.APPLIED, holder.navigateSession(
        menu("replacement"), new NavigationRequest(NavigationMode.REPLACE, "replacement")));
    assertEquals("root", holder.lastSessionId());

    assertEquals(NavigationResult.APPLIED, holder.navigateSession(
        menu("root"), new NavigationRequest(NavigationMode.HOME, null)));
    assertNull(holder.lastSessionId());
    assertEquals("root", holder.rootSessionId());
  }

  @Test
  public void pushingTheSameMenuTwiceRecordsBothEntries() {
    SessionHolder holder = holder(new PlayerSnapshotStore<>());
    holder.openSession(menu("root"), null);
    holder.navigateSession(menu("alpha"), new NavigationRequest(NavigationMode.PUSH, "alpha"));

    assertEquals(NavigationResult.APPLIED, holder.navigateSession(
        menu("alpha"), new NavigationRequest(NavigationMode.PUSH, "alpha")));

    assertEquals("a repeat push is recorded, not collapsed", "alpha", holder.lastSessionId());
    assertEquals(NavigationResult.APPLIED, holder.navigateSession(
        menu("alpha"), new NavigationRequest(NavigationMode.BACK, null)));
    assertEquals("root", holder.lastSessionId());
  }

  @Test
  public void openingAFreshSessionResetsTheRootAndDropsTheHistory() {
    SessionHolder holder = holder(new PlayerSnapshotStore<>());
    holder.openSession(menu("root"), null);
    holder.navigateSession(menu("alpha"), new NavigationRequest(NavigationMode.PUSH, "alpha"));
    assertTrue(holder.closeSession(true, HoloCloseReason.CLOSED_BY_COMMAND));
    assertEquals("alpha", holder.lastSessionId());
    assertEquals("root", holder.rootSessionId());

    holder.openSession(menu("gamma"), null);

    assertNull("a fresh open starts a new stack", holder.lastSessionId());
    assertEquals("gamma", holder.rootSessionId());
  }

  @Test
  public void replacingWithNothingOpenAdoptsTheTargetAsTheRoot() {
    SessionHolder holder = holder(new PlayerSnapshotStore<>());

    assertEquals(NavigationResult.APPLIED, holder.navigateSession(
        menu("alpha"), new NavigationRequest(NavigationMode.REPLACE, "alpha")));

    assertEquals("alpha", holder.rootSessionId());
    assertNull(holder.lastSessionId());
  }

  @Test
  public void backAndHomeRejectAMenuThatIsNotTheRecordedTarget() {
    SessionHolder holder = holder(new PlayerSnapshotStore<>());
    holder.openSession(menu("root"), null);
    holder.navigateSession(menu("alpha"), new NavigationRequest(NavigationMode.PUSH, "alpha"));

    assertEquals(NavigationResult.NO_HISTORY, holder.navigateSession(
        menu("beta"), new NavigationRequest(NavigationMode.BACK, null)));
    assertEquals(NavigationResult.NO_HISTORY, holder.navigateSession(
        menu("beta"), new NavigationRequest(NavigationMode.HOME, null)));

    assertEquals("a rejected navigation must not disturb the stack", "root", holder.lastSessionId());
    assertEquals("root", holder.rootSessionId());
  }

  @Test
  public void closingWithoutHistoryForgetsTheRootAsWell() {
    SessionHolder holder = holder(new PlayerSnapshotStore<>());
    holder.openSession(menu("root"), null);
    holder.navigateSession(menu("alpha"), new NavigationRequest(NavigationMode.PUSH, "alpha"));

    assertTrue(holder.closeSession(false, HoloCloseReason.CLOSED_BY_COMMAND));

    assertNull(holder.lastSessionId());
    assertNull(holder.rootSessionId());
  }

  @Test
  public void closingTheSessionClearsTheStore() {
    PlayerSnapshotStore<String> openMenus = new PlayerSnapshotStore<>();
    SessionHolder holder = holder(openMenus);

    holder.openSession(menu("alpha"), null);
    assertEquals("alpha", openMenus.get(PLAYER));

    assertTrue(holder.closeSession(true, HoloCloseReason.CLOSED_BY_COMMAND));

    assertFalse(holder.hasSession());
    assertEquals("alpha", holder.lastSessionId());
    assertNull("a closed menu must not stay published", openMenus.get(PLAYER));
  }

  @Test
  public void quitTeardownClearsTheStore() {
    PlayerSnapshotStore<String> openMenus = new PlayerSnapshotStore<>();
    SessionHolder holder = holder(openMenus);

    holder.openSession(menu("alpha"), null);
    holder.close(HoloCloseReason.QUIT);

    assertNull("tearing a holder down must not leave a stale entry behind", openMenus.get(PLAYER));
  }

  @Test
  public void offlinePlayersNeverPublishAMenu() {
    PlayerSnapshotStore<String> openMenus = new PlayerSnapshotStore<>();
    AtomicBoolean online = new AtomicBoolean(false);
    SessionHolder holder = new SessionHolder(player(new AtomicReference<>(PLAYER), new AtomicInteger(), online), openMenus);

    holder.openSession(menu("alpha"), null);

    assertFalse(holder.hasSession());
    assertNull(openMenus.get(PLAYER));
  }

  @Test
  public void theExpansionAnswersFromTheSameStoreTheHolderWritesTo() {
    PlayerSnapshotStore<String> openMenus = new PlayerSnapshotStore<>();
    SessionHolder holder = holder(openMenus);
    GlossPlaceholderExpansion expansion = new GlossPlaceholderExpansion(openMenus, Logger.getAnonymousLogger());

    assertEquals(PlaceholderValues.FALSE, expansion.onRequest(offlinePlayer(), "menu.open"));
    assertEquals(PlaceholderValues.UNAVAILABLE, expansion.onRequest(offlinePlayer(), "menu.id"));

    holder.openSession(menu("alpha"), null);

    assertEquals(PlaceholderValues.TRUE, expansion.onRequest(offlinePlayer(), "menu.open"));
    assertEquals("alpha", expansion.onRequest(offlinePlayer(), "menu.id"));

    holder.openSession(menu("beta"), null);

    assertEquals("beta", expansion.onRequest(offlinePlayer(), "menu.id"));

    holder.close(HoloCloseReason.CLOSED_BY_COMMAND);

    assertEquals(PlaceholderValues.FALSE, expansion.onRequest(offlinePlayer(), "menu.open"));
    assertEquals(PlaceholderValues.UNAVAILABLE, expansion.onRequest(offlinePlayer(), "menu.id"));
  }

  @Test
  public void componentsBuiltDuringConstructionAlreadySeeTheMenuIdPublished() {
    PlayerSnapshotStore<String> openMenus = new PlayerSnapshotStore<>();
    SessionHolder holder = holder(openMenus);
    GlossPlaceholderExpansion expansion = new GlossPlaceholderExpansion(openMenus, Logger.getAnonymousLogger());
    AtomicReference<String> seenId = new AtomicReference<>();
    AtomicReference<String> seenOpen = new AtomicReference<>();

    holder.openSession(menu("alpha", () -> {
      seenId.set(expansion.onRequest(offlinePlayer(), "menu.id"));
      seenOpen.set(expansion.onRequest(offlinePlayer(), "menu.open"));
      return null;
    }), null);

    assertEquals("a toggle condition or icon built in the session constructor must resolve the menu id",
        "alpha", seenId.get());
    assertEquals("a toggle built in the session constructor must see the menu as open",
        PlaceholderValues.TRUE, seenOpen.get());
  }

  @Test
  public void aComponentThatThrowsDuringConstructionLeavesNoMenuIdPublished() {
    PlayerSnapshotStore<String> openMenus = new PlayerSnapshotStore<>();
    SessionHolder holder = holder(openMenus);

    try {
      holder.openSession(menu("alpha", () -> {
        throw new IllegalStateException("component blew up");
      }), null);
      fail("a component that throws must not be swallowed by the holder");
    } catch (IllegalStateException expected) {
      assertEquals("component blew up", expected.getMessage());
    }

    assertFalse(holder.hasSession());
    assertNull("a failed open must not leak a published menu id", openMenus.get(PLAYER));
  }

  @Test
  public void aComponentThatThrowsDuringOpenPreservesTheCurrentSession() {
    GlossTelemetry.clear();
    PlayerSnapshotStore<String> openMenus = new PlayerSnapshotStore<>();
    SessionHolder holder = holder(openMenus);
    AtomicInteger closeCalls = new AtomicInteger();
    AtomicReference<HoloCloseReason> replacedReason = new AtomicReference<>();
    AtomicReference<HoloCloseReason> failedReason = new AtomicReference<>();
    ApiMenuHandle replaced = handle("alpha", replacedReason);
    ApiMenuHandle failed = handle("beta", failedReason);

    try {
      holder.openSession(menu("root"), null);
      holder.openSession(menu("alpha"), replaced);
      assertEquals(1, GlossTelemetry.menusOpen());
      assertEquals("root", holder.lastSessionId());
      assertEquals("root", holder.rootSessionId());

      try {
        holder.openSession(failingOpenMenu("beta", closeCalls), failed);
        fail("an open failure must escape the holder");
      } catch (IllegalStateException expected) {
        assertEquals("component open failed", expected.getMessage());
      }

      assertTrue(holder.hasSession());
      assertEquals("root", holder.lastSessionId());
      assertEquals("root", holder.rootSessionId());
      assertEquals("alpha", openMenus.get(PLAYER));
      assertEquals(1, GlossTelemetry.menusOpen());
      assertEquals(1, closeCalls.get());
      assertEquals(HoloMenuState.OPEN, replaced.state());
      assertNull(replacedReason.get());
      assertEquals(HoloMenuState.FAILED, failed.state());
      assertEquals(HoloCloseReason.OPEN_FAILED, failedReason.get());
    } finally {
      GlossTelemetry.clear();
    }
  }

  private static SessionHolder holder(PlayerSnapshotStore<String> openMenus) {
    return new SessionHolder(player(new AtomicReference<>(PLAYER), new AtomicInteger(), new AtomicBoolean(true)), openMenus);
  }

  private static MenuDefinitionData menu(String id) {
    return menu(id, new Vector());
  }

  private static MenuDefinitionData menu(String id, Vector offset) {
    MenuDefinitionData data = new MenuDefinitionData(offset, false, false, 8.0D, false, false,
        List.<MenuComponentData>of());
    data.setId(id);
    return data;
  }

  private static MenuDefinitionData menu(String id, Supplier<MenuComponent<?>> onCreate) {
    MenuComponentData component = new MenuComponentData("probe", new Vector(0, 0, 0), new ProbeComponentData(onCreate));
    MenuDefinitionData data = new MenuDefinitionData(new Vector(0, 0, 0), false, false, 8.0D, false, false,
        List.of(component));
    data.setId(id);
    return data;
  }

  private static MenuDefinitionData failingOpenMenu(String id, AtomicInteger closeCalls) {
    MenuComponentData component = new MenuComponentData("probe", new Vector(), new OpeningFailureData(closeCalls));
    MenuDefinitionData data = new MenuDefinitionData(new Vector(), false, false, 8.0D, false, false, List.of(component));
    data.setId(id);
    return data;
  }

  private static ApiMenuHandle handle(String menuId, AtomicReference<HoloCloseReason> reason) {
    ApiMenuHandle handle = new ApiMenuHandle(PLAYER, menuId, new ApiOwner("test", () -> true), Map.of(), Set.of(),
        Logger.getAnonymousLogger(), (ignored, closeReason) -> {
        }, ignored -> {
        });
    handle.onClosed(reason::set);
    return handle;
  }

  private record ProbeComponentData(Supplier<MenuComponent<?>> onCreate) implements ComponentData {
    @Override
    public MenuComponentType getType() {
      return MenuComponentType.TOGGLE;
    }

    @Override
    public MenuComponent<?> createComponent(MenuSession session, MenuComponentData data) {
      return onCreate.get();
    }
  }

  private record OpeningFailureData(AtomicInteger closeCalls) implements ComponentData {
    @Override
    public MenuComponentType getType() {
      return MenuComponentType.DECO;
    }

    @Override
    public MenuComponent<?> createComponent(MenuSession session, MenuComponentData data) {
      return new OpeningFailureComponent(session, data);
    }
  }

  private static final class OpeningFailureComponent extends MenuComponent<OpeningFailureData> {
    private OpeningFailureComponent(MenuSession session, MenuComponentData data) {
      super(session, data);
    }

    @Override
    public void open() {
      throw new IllegalStateException("component open failed");
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

  private static Player player(AtomicReference<UUID> identity, AtomicInteger idReads, AtomicBoolean online) {
    return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getUniqueId" -> {
            idReads.incrementAndGet();
            yield identity.get();
          }
          case "isOnline" -> online.get();
          case "getName" -> "tester";
          case "getLocation" -> new Location(null, 0, 64, 0);
          case "getEyeLocation" -> new Location(null, 0, 65.62D, 0);
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "Player[" + identity.get() + "]";
          default -> throw new UnsupportedOperationException("session holder touched " + method.getName());
        });
  }

  private static OfflinePlayer offlinePlayer() {
    return (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(),
        new Class<?>[]{OfflinePlayer.class}, (proxy, method, args) -> switch (method.getName()) {
          case "getUniqueId" -> PLAYER;
          case "hashCode" -> PLAYER.hashCode();
          case "equals" -> proxy == args[0];
          case "toString" -> "OfflinePlayer[" + PLAYER + "]";
          default -> throw new UnsupportedOperationException("placeholder resolution touched " + method.getName());
        });
  }
}
