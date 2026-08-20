package art.arcane.gloss.panel;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.menu.MenuCatalog;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.doc.StorageTaskRunner;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The two zero-work paths of the panel driver: a server with no panels never reaches a viewer at
 * all, and a viewer that stays put reuses the candidate list it already has — but only while the
 * index it came from is still current, which is what keeps a moved panel from lingering on a
 * standing viewer's screen.
 */
public class PanelRuntimeTickBailTest {
  private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000601");
  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000602");

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  private final World world = world();
  private Object previousServer;
  private Gloss previousInstance;
  private MenuCatalog menuCatalog;
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
    Files.writeString(new File(menus, "bail.json").toPath(), """
        {
          "offset": [0, 0, 0],
          "components": []
        }
        """, StandardCharsets.UTF_8);
    menuCatalog = new MenuCatalog(configDir);
    CharacterizationSupport.setField(gloss, "menuCatalog", menuCatalog);

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
    if (menuCatalog != null) {
      menuCatalog.shutdown();
    }
    CharacterizationSupport.restoreGloss(previousInstance);
    CharacterizationSupport.restoreServer((org.bukkit.Server) previousServer);
  }

  @Test
  public void aServerWithNoPanelsNeverReachesAViewer() throws Exception {
    assertTrue("nothing placed, nothing previewed, nobody viewing", idle());

    PanelDefinition board = publishBoard();
    assertFalse("a placed panel is work", idle());

    CompletableFuture<PanelDefinition> deleted = service.delete(board.id(), board.revision());
    runner.runAll();
    deleted.join();
    assertTrue("deleting the last panel returns the driver to idle", idle());
  }

  @Test
  public void aRegisteredViewerKeepsTheDriverAwake() throws Exception {
    publishBoard();
    Object state = viewerState(player(new AtomicReference<>(at(10.0D, 64.0D, 0.0D))));
    tick(state);
    assertEquals(1, runtime.visibleBoardCount());
    assertFalse(idle());
  }

  @Test
  public void aStandingViewerStillSeesTheIndexMoveUnderIt() throws Exception {
    PanelDefinition board = publishBoard();
    AtomicReference<Location> position = new AtomicReference<>(at(10.0D, 64.0D, 0.0D));
    Object state = viewerState(player(position));

    tick(state);
    assertEquals("the panel is well inside range", 1, runtime.visibleBoardCount());

    tick(state);
    assertEquals("a viewer that has not moved keeps what it had", 1, runtime.visibleBoardCount());

    PanelDefinition moved = board.withTransform(
        PanelTransform.at("example:world", WORLD_UUID, 4000.0D, 64.0D, 4000.0D, 0.0D));
    runtime.boardUpdated(board, moved);
    tick(state);

    assertEquals("the cached candidate list is keyed on the index generation, so a panel that "
        + "moved away closes even for a viewer that never moved", 0, runtime.visibleBoardCount());
  }

  // ---------------------------------------------------------------------
  // Plumbing
  // ---------------------------------------------------------------------

  private boolean idle() {
    return (boolean) CharacterizationSupport.invoke(runtime, "idle", new Class<?>[0]);
  }

  private PanelDefinition publishBoard() {
    CompletableFuture<PanelDefinition> created = service.create(PanelDefinition.create("bail-board", "bail",
        PanelTransform.at("example:world", WORLD_UUID, 0.0D, 64.0D, 0.0D, 0.0D)));
    runner.runAll();
    return created.join();
  }

  @SuppressWarnings("unchecked")
  private Object viewerState(Player player) throws Exception {
    Class<?> stateType = null;
    for (Class<?> nested : PanelRuntimeManager.class.getDeclaredClasses()) {
      if (nested.getSimpleName().equals("ViewerState")) {
        stateType = nested;
      }
    }
    if (stateType == null) {
      throw new AssertionError("PanelRuntimeManager.ViewerState was renamed");
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
          case "getName" -> "bailviewer";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "Player[bailviewer]";
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
          case "toString" -> "World[bail]";
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
  private static final class QueuedRunner implements StorageTaskRunner {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public StorageTaskHandle submit(Runnable task) {
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
