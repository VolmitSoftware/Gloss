package art.arcane.gloss.panel;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.bedrock.BedrockPolicy;
import art.arcane.gloss.bedrock.BedrockService;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Panels are block and text displays, so a Bedrock viewer standing on top of one must open no view
 * at all; a Java viewer at the same spot still opens it.
 */
public class BedrockPanelAudienceTest {
  private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000601");
  private static final UUID BEDROCK_VIEWER = new UUID(0L, 0x602L);
  private static final UUID JAVA_VIEWER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  private final World world = world();
  private Object previousServer;
  private Gloss previousInstance;
  private MenuCatalog menuCatalog;
  private QueuedRunner runner;
  private PanelService service;
  private PanelRuntimeManager runtime;
  private final AtomicLong clock = new AtomicLong();

  @Before
  public void bootHeadlessRuntime() throws Exception {
    previousServer = CharacterizationSupport.installServer(
        CharacterizationSupport.server(Map.of(WORLD_UUID, world)));
    Gloss gloss = CharacterizationSupport.bareGloss(
        CharacterizationSupport.server(Map.of(WORLD_UUID, world)));
    CharacterizationSupport.setField(gloss, "persistenceCoordinator", new GlossPersistenceCoordinator());
    BedrockService bedrock = new BedrockService(BedrockService.Detection.UUID);
    CharacterizationSupport.setField(gloss, "bedrock", bedrock);
    CharacterizationSupport.setField(gloss, "bedrockPolicy", new BedrockPolicy(bedrock,
        () -> new GlossConfig.Bedrock("uuid", true, true, true, true, true, true)));
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
    menuCatalog = new MenuCatalog(configDir);
    CharacterizationSupport.setField(gloss, "menuCatalog", menuCatalog);

    runner = new QueuedRunner();
    Path panelData = temp.newFolder("panels").toPath();
    service = new PanelService(new PanelService.Dependencies(
        new PanelRepository(panelData, clock::get), runner,
        CharacterizationSupport.mutedLogger(), new GlossPersistenceCoordinator(), () -> {
    }));
    runtime = new PanelRuntimeManager(gloss, service);
    service.start();
    runner.runAll();
    service.create(PanelDefinition.create("bedrock-board", "boundary",
        PanelTransform.at("example:world", WORLD_UUID, 0.0D, 64.0D, 0.0D, 0.0D)));
    runner.runAll();
  }

  @After
  public void restoreStatics() throws Exception {
    if (runtime != null) {
      runtime.shutdown();
    }
    if (service != null) {
      service.shutdown();
    }
    if (menuCatalog != null) {
      menuCatalog.shutdown();
    }
    CharacterizationSupport.restoreGloss(previousInstance);
    CharacterizationSupport.restoreServer((org.bukkit.Server) previousServer);
  }

  @Test
  public void aBedrockViewerOpensNoPanelView() throws Exception {
    tick(viewerState(player(BEDROCK_VIEWER, "bedrockviewer"), BEDROCK_VIEWER));
    assertEquals("a Bedrock viewer must open no panel view", 0, runtime.visibleBoardCount());
  }

  @Test
  public void aJavaViewerAtTheSameSpotStillOpensThePanel() throws Exception {
    tick(viewerState(player(JAVA_VIEWER, "javaviewer"), JAVA_VIEWER));
    assertEquals("a Java viewer must still open the panel", 1, runtime.visibleBoardCount());
  }

  @SuppressWarnings("unchecked")
  private Object viewerState(Player player, UUID viewerId) throws Exception {
    Class<?> stateType = null;
    for (Class<?> nested : PanelRuntimeManager.class.getDeclaredClasses()) {
      if (nested.getSimpleName().equals("ViewerState")) {
        stateType = nested;
      }
    }
    if (stateType == null) {
      throw new AssertionError("PanelRuntimeManager.ViewerState is part of this pin; "
          + "if it was renamed, update the pin deliberately");
    }
    Constructor<?> constructor = stateType.getDeclaredConstructor(PanelRuntimeManager.class, Player.class);
    constructor.setAccessible(true);
    Object state = constructor.newInstance(runtime, player);
    ((Map<UUID, Object>) CharacterizationSupport.getField(runtime, "viewers")).put(viewerId, state);
    return state;
  }

  private static void tick(Object state) {
    CharacterizationSupport.invoke(state, "tick", new Class<?>[0]);
  }

  private Player player(UUID id, String name) {
    Location position = new Location(world, 0.0D, 64.0D, 0.0D);
    return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getUniqueId" -> id;
          case "isOnline" -> true;
          case "getLocation" -> position.clone();
          case "getEyeLocation" -> position.clone().add(0.0D, 1.62D, 0.0D);
          case "hasPermission" -> true;
          case "getName" -> name;
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "Player[" + name + "]";
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
