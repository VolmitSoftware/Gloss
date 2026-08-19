package art.arcane.gloss.panel;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.ConfigManager;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Characterization pins for the panel runtime viewer loop (plan item T15/D5-D6): with zero boards
 * a viewer tick opens nothing, view membership at the exact view-range boundary is inclusive,
 * leaving range closes the view, and {@code findClickTarget} answers null for absent viewers,
 * empty view sets, and viewers outside the interaction range. Outcomes are observed through
 * {@code visibleBoardCount()} and {@code findClickTarget(..)} only, so index re-publication and
 * zero-work bail-outs stay invisible while membership changes break the pins.
 */
public class CharacterizationPanelRuntimeTest {
  private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000501");
  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000502");

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  private final World world = world();
  private Object previousServer;
  private Gloss previousInstance;
  private ConfigManager configManager;
  private QueuedRunner runner;
  private PanelService service;
  private PanelRuntimeManager runtime;

  @Before
  public void bootHeadlessRuntime() throws Exception {
    previousServer = CharacterizationSupport.installServer(
        CharacterizationSupport.server(Map.of(WORLD_UUID, world)));
    Gloss gloss = CharacterizationSupport.bareGloss(
        CharacterizationSupport.server(Map.of(WORLD_UUID, world)));
    CharacterizationSupport.setField(gloss, "persistenceCoordinator", new GlossPersistenceCoordinator());
    previousInstance = CharacterizationSupport.installGloss(gloss);

    File configDir = temp.newFolder("gloss-config");
    File menus = new File(configDir, "menus");
    assertTrue(menus.mkdirs());
    Files.writeString(new File(menus, "boundary.json").toPath(), """
        {
          "offset": [0, 0, 0],
          "components": []
        }
        """, StandardCharsets.UTF_8);
    configManager = new ConfigManager(configDir);
    CharacterizationSupport.setField(gloss, "configManager", configManager);

    runner = new QueuedRunner();
    service = new PanelService(new PanelService.Dependencies(
        new PanelRepository(temp.newFolder("panels")), runner,
        CharacterizationSupport.mutedLogger(), new GlossPersistenceCoordinator(), () -> {
    }));
    runtime = new PanelRuntimeManager(gloss, service);
    service.start();
    runner.runAll();
  }

  @After
  public void restoreStatics() throws Exception {
    if (runtime != null) {
      runtime.shutdown();
    }
    if (configManager != null) {
      configManager.shutdown();
    }
    CharacterizationSupport.restoreGloss(previousInstance);
    CharacterizationSupport.restoreServer((org.bukkit.Server) previousServer);
  }

  @Test
  public void zeroBoardsMeansNoSessionsEverOpen() throws Exception {
    AtomicReference<Location> position = new AtomicReference<>(at(0.0D, 64.0D, 0.0D));
    Player player = player(position);
    assertNull("no viewer state, no click target", runtime.findClickTarget(player));

    Object state = viewerState(player);
    for (int tick = 0; tick < 5; tick++) {
      tick(state);
    }

    assertEquals("with zero boards a viewer tick must never open a session",
        0, runtime.visibleBoardCount());
    assertNull("an empty view set must yield no click target", runtime.findClickTarget(player));
  }

  @Test
  public void viewMembershipAtTheExactRangeBoundaryIsInclusive() throws Exception {
    publishBoundaryBoard();
    AtomicReference<Location> position = new AtomicReference<>(at(65.0D, 64.0D, 0.0D));
    Player player = player(position);
    Object state = viewerState(player);

    tick(state);
    assertEquals("just outside the 64-block view range no view may open",
        0, runtime.visibleBoardCount());

    position.set(at(64.0D, 64.0D, 0.0D));
    tick(state);
    assertEquals("at exactly the view range the view opens (the boundary is inclusive)",
        1, runtime.visibleBoardCount());

    assertNull("a component-less panel menu outside interaction range yields no click target",
        runtime.findClickTarget(player));

    position.set(at(65.0D, 64.0D, 0.0D));
    tick(state);
    assertEquals("leaving the view range closes the view", 0, runtime.visibleBoardCount());

    position.set(at(10.0D, 64.0D, 0.0D));
    tick(state);
    assertEquals("re-entering re-opens it", 1, runtime.visibleBoardCount());
  }

  @Test
  public void shutdownClosesEverythingAndStopsAnsweringClicks() throws Exception {
    publishBoundaryBoard();
    AtomicReference<Location> position = new AtomicReference<>(at(10.0D, 64.0D, 0.0D));
    Player player = player(position);
    Object state = viewerState(player);
    tick(state);
    assertEquals(1, runtime.visibleBoardCount());

    runtime.shutdown();

    assertEquals(0, runtime.visibleBoardCount());
    assertNull(runtime.findClickTarget(player));
  }

  // ---------------------------------------------------------------------
  // Plumbing
  // ---------------------------------------------------------------------

  private void publishBoundaryBoard() {
    service.create(PanelDefinition.create("boundary-board", "boundary",
        PanelTransform.at("example:world", WORLD_UUID, 0.0D, 64.0D, 0.0D, 0.0D)));
    runner.runAll();
    assertEquals(64.0D, service.maximumViewRange(), 0.0D);
  }

  /** The runtime's private per-viewer state, registered so {@code findClickTarget} can see it. */
  @SuppressWarnings("unchecked")
  private Object viewerState(Player player) throws Exception {
    Class<?> stateType = null;
    for (Class<?> nested : PanelRuntimeManager.class.getDeclaredClasses()) {
      if (nested.getSimpleName().equals("ViewerState")) {
        stateType = nested;
      }
    }
    if (stateType == null) {
      throw new AssertionError("PanelRuntimeManager.ViewerState is part of a characterization pin; "
          + "if it was renamed, update the pin deliberately");
    }
    Constructor<?> constructor = stateType.getDeclaredConstructor(PanelRuntimeManager.class, Player.class);
    constructor.setAccessible(true);
    Object state = constructor.newInstance(runtime, player);
    ((Map<UUID, Object>) CharacterizationSupport.getField(runtime, "viewers")).put(VIEWER, state);
    return state;
  }

  private static void tick(Object state) {
    CharacterizationSupport.invoke(state, "tick", new Class<?>[0]);
  }

  private Location at(double x, double y, double z) {
    return new Location(world, x, y, z);
  }

  private Player player(AtomicReference<Location> position) {
    return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getUniqueId" -> VIEWER;
          case "isOnline" -> true;
          case "getLocation" -> position.get().clone();
          case "getEyeLocation" -> position.get().clone().add(0.0D, 1.62D, 0.0D);
          case "hasPermission" -> true;
          case "getName" -> "panelviewer";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "Player[panelviewer]";
          default -> throw new UnsupportedOperationException(
              "the panel viewer loop touched Player#" + method.getName());
        });
  }

  private static World world() {
    return (World) CharacterizationSupport.proxy(new Class<?>[]{World.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getUID" -> WORLD_UUID;
          case "getKey" -> new NamespacedKey("example", "world");
          case "getName" -> "world";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "World[panel]";
          default -> defaultValue(method.getReturnType());
        });
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive() || type == void.class) {
      return null;
    }
    if (type == boolean.class) {
      return false;
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
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    return 0;
  }

  /** Runs submitted service tasks on demand, on the test thread. */
  private static final class QueuedRunner implements PanelTaskRunner {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public PanelTaskHandle submit(Runnable task) {
      tasks.addLast(task);
      return () -> tasks.remove(task);
    }

    private void runAll() {
      while (!tasks.isEmpty()) {
        tasks.pollFirst().run();
      }
    }
  }
}
