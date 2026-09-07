package art.arcane.gloss.panel;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.menu.MenuCatalog;
import art.arcane.gloss.doc.StorageTaskRunner;
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
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The per-tick cost pins of the panel driver at a full server: a viewer samples a permission node
 * once per TTL rather than once per candidate panel per tick, a move inside one chunk records
 * nothing, and the chunk-padded candidate prefilter still sees every panel any position in that
 * chunk could reach.
 */
public class PanelRuntimeCostTest {
  private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000801");
  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000802");
  private static final String VIEW_NODE = "gloss.panel.costpin";

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
    Files.writeString(new File(menus, "cost.json").toPath(), """
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
  public void aViewerSamplesAPermissionNodeOncePerTtlNotOncePerTick() throws Exception {
    publishGatedBoard();
    AtomicInteger permissionLookups = new AtomicInteger();
    Player player = player(new AtomicReference<>(at(10.0D, 64.0D, 0.0D)), permissionLookups);
    Object state = viewerState(player);

    for (int tick = 0; tick < 20; tick++) {
      tick(state);
    }

    assertEquals("the panel opened, so its view permission was consulted",
        1, runtime.visibleBoardCount());
    assertEquals("a permission node costs one lookup per viewer per TTL, not one per tick",
        1, permissionLookups.get());
  }

  @Test
  public void aViewerThatWalksOutOfRangeDropsItsScratchCandidateSet() throws Exception {
    publishHiddenBoard();
    AtomicReference<Location> position = new AtomicReference<>(at(10.0D, 64.0D, 0.0D));
    Object state = viewerState(player(position, new AtomicInteger()));

    tick(state);
    assertEquals("a hidden panel is still considered, so the scratch set holds it",
        1, scratchCandidates(state).size());

    position.set(at(4000.0D, 64.0D, 4000.0D));
    tick(state);

    assertTrue("walking out of range must drop the retained candidate set, not keep it until quit",
        scratchCandidates(state).isEmpty());
  }

  @Test
  public void movingInsideOneChunkRecordsNothing() throws Exception {
    Player player = player(new AtomicReference<>(at(0.0D, 64.0D, 0.0D)), new AtomicInteger());
    recordPresence(player, at(1.0D, 64.0D, 1.0D));
    Object first = presencePosition();

    recordPresence(player, at(15.9D, 64.0D, 15.9D));

    assertSame("a move inside the recorded chunk must not allocate or publish a new position",
        first, presencePosition());
  }

  @Test
  public void crossingAChunkBorderRecordsTheNewPosition() throws Exception {
    Player player = player(new AtomicReference<>(at(0.0D, 64.0D, 0.0D)), new AtomicInteger());
    recordPresence(player, at(1.0D, 64.0D, 1.0D));
    Object first = presencePosition();

    recordPresence(player, at(16.0D, 64.0D, 1.0D));

    assertNotSame("crossing into another chunk must record the new position",
        first, presencePosition());
  }

  @Test
  public void theChunkPaddedPrefilterStillSeesAPanelAtTheFarEdgeOfTheChunk() throws Exception {
    publishBoard("edge", 79.0D, 0.0D);
    Player player = player(new AtomicReference<>(at(0.1D, 64.0D, 0.1D)), new AtomicInteger());
    recordPresence(player, at(0.1D, 64.0D, 0.1D));

    assertEquals(64.0D, service.maximumViewRange(), 0.0D);
    assertTrue("a viewer that entered the chunk at its near edge must still be offered a panel "
            + "that any position in that chunk can reach",
        prefilter(service.maximumViewRange() + chunkQueryPadding()));
    assertFalse("a panel beyond the padded window is still filtered out",
        prefilter(1.0D));
  }

  // ---------------------------------------------------------------------
  // Plumbing
  // ---------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static Map<UUID, PanelDefinition> scratchCandidates(Object state) throws Exception {
    return (Map<UUID, PanelDefinition>) CharacterizationSupport.getField(state, "effectiveCandidates");
  }

  private void publishHiddenBoard() {
    CompletableFuture<PanelDefinition> created = service.create(
        PanelDefinition.create("cost-hidden", "cost",
                PanelTransform.at("example:world", WORLD_UUID, 0.0D, 64.0D, 0.0D, 0.0D))
            .withVisibility(PanelVisibility.hidden()));
    runner.runAll();
    created.join();
  }

  private void recordPresence(Player player, Location location) {
    CharacterizationSupport.invoke(runtime, "recordPresence",
        new Class<?>[]{Player.class, Location.class}, player, location);
  }

  @SuppressWarnings("unchecked")
  private Object presencePosition() throws Exception {
    Map<UUID, Object> presences =
        (Map<UUID, Object>) CharacterizationSupport.getField(runtime, "playerPresences");
    Object presence = presences.get(VIEWER);
    if (presence == null) {
      throw new AssertionError("no presence was recorded for the viewer");
    }
    return CharacterizationSupport.getField(presence, "position");
  }

  @SuppressWarnings("unchecked")
  private boolean prefilter(double queryRange) throws Exception {
    Map<UUID, Object> presences =
        (Map<UUID, Object>) CharacterizationSupport.getField(runtime, "playerPresences");
    Object presence = presences.get(VIEWER);
    Object index = CharacterizationSupport.getField(runtime, "effectiveIndex");
    Method hasCandidate = presence.getClass()
        .getDeclaredMethod("hasCandidate", PanelSpatialIndex.class, double.class);
    hasCandidate.setAccessible(true);
    return (boolean) hasCandidate.invoke(presence, index, queryRange);
  }

  private static double chunkQueryPadding() throws Exception {
    java.lang.reflect.Field field =
        PanelRuntimeManager.class.getDeclaredField("CHUNK_QUERY_PADDING");
    field.setAccessible(true);
    return field.getDouble(null);
  }

  private void publishGatedBoard() {
    CompletableFuture<PanelDefinition> created = service.create(
        PanelDefinition.create("cost-gated", "cost",
                PanelTransform.at("example:world", WORLD_UUID, 0.0D, 64.0D, 0.0D, 0.0D))
            .withVisibility(PanelVisibility.permission(VIEW_NODE, null)));
    runner.runAll();
    created.join();
  }

  private void publishBoard(String id, double x, double z) {
    CompletableFuture<PanelDefinition> created = service.create(PanelDefinition.create(id, "cost",
        PanelTransform.at("example:world", WORLD_UUID, x, 64.0D, z, 0.0D)));
    runner.runAll();
    created.join();
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

  private Player player(AtomicReference<Location> position, AtomicInteger permissionLookups) {
    return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getUniqueId" -> VIEWER;
          case "isOnline" -> true;
          case "getLocation" -> position.get().clone();
          case "getEyeLocation" -> position.get().clone().add(0.0D, 1.62D, 0.0D);
          case "hasPermission" -> {
            permissionLookups.incrementAndGet();
            yield true;
          }
          case "getName" -> "costviewer";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "Player[costviewer]";
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
          case "toString" -> "World[cost]";
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
