package art.arcane.gloss.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.api.HoloCloseReason;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.config.menu.MenuDocumentParser;
import art.arcane.gloss.menu.components.ButtonComponent;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.gloss.util.common.DisplayEntity.MetadataIndex;
import art.arcane.volmlib.util.bukkit.papi.PlayerSnapshotStore;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins what {@link DisplayEntityManager} puts on the wire for a single-field change (plan item
 * T20/C5) and for icon teardown (C11).
 *
 * <p>Two things are at stake. First the <b>index mapping</b>: the manager now sends metadata
 * subsets, so an off-by-one index would ship a scale as a translation and nothing would notice
 * until a client rendered it. Second the <b>elision</b>: {@code orient} sends nothing when the
 * orientation it is asked for is the one already applied, which is what stops every reposition
 * dragging a 22-entry metadata block behind it.
 */
public class DisplayEntityManagerPacketShapeTest {

  private static final int TRANSLATION = 11;
  private static final int SCALE = 12;
  private static final int LEFT_ROTATION = 13;
  private static final int CONTENT = 23;
  private static final int TEXT_BACKGROUND = 25;

  private static final List<PacketWrapper<?>> SENT = new ArrayList<>();

  @BeforeClass
  public static void installPacketEventsApi() {
    PacketEvents.setAPI(new RecordingPacketEventsApi());
  }

  @AfterClass
  public static void clearPacketEventsApi() {
    PacketEvents.setAPI(null);
  }

  @After
  public void clearSent() {
    SENT.clear();
  }

  // ---------------------------------------------------------------------
  // Index mapping
  // ---------------------------------------------------------------------

  @Test
  public void hiddenMenusAndComponentsDespawnAndRejectActionsUntilShownAgain() throws ReflectiveOperationException {
    for (String gate : List.of("menu", "component")) {
      assertShowLifecycle(gate);
    }
  }

  private static void assertShowLifecycle(String gate) throws ReflectiveOperationException {
    AtomicLong time = new AtomicLong(18000L);
    UUID worldId = UUID.randomUUID();
    World world = (World) CharacterizationSupport.proxy(new Class<?>[]{World.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getName" -> "world";
          case "getUID" -> worldId;
          case "getTime" -> time.get();
          default -> CharacterizationSupport.identity(proxy, method, args);
        });
    Server server = CharacterizationSupport.server(Map.of(worldId, world));
    Object previousServer = CharacterizationSupport.installServer(server);
    Gloss plugin = CharacterizationSupport.bareGloss(server);
    Gloss previousPlugin = CharacterizationSupport.installGloss(plugin);
    CharacterizationSupport.setField(plugin, "text", new TextPipeline(plugin));
    List<String> commands = new ArrayList<>();
    AtomicReference<Location> eye = new AtomicReference<>(new Location(world, 0D, 64D, 0D));
    UUID playerId = UUID.randomUUID();
    Player player = (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getUniqueId" -> playerId;
          case "getName" -> "show-viewer";
          case "getWorld" -> world;
          case "getLocation" -> new Location(world, 0D, 64D, 0D);
          case "getEyeLocation" -> eye.get().clone();
          case "isOnline" -> true;
          case "performCommand" -> {
            commands.add((String) args[0]);
            yield true;
          }
          default -> CharacterizationSupport.identity(proxy, method, args);
        });
    SessionHolder holder = new SessionHolder(player, new PlayerSnapshotStore<>());
    int initialEntities = DisplayEntityManager.totalCount();
    int initialVisible = DisplayEntityManager.visibleCount();
    SENT.clear();
    try {
      String condition = "\"show\": \"{{world.time < 12000}}\",";
      MenuDefinitionData menu = MenuDocumentParser.parse("show-packets", """
          {
            %s
            "offset": [0, 0, 3],
            "particleLayers": [],
            "components": [{
              %s
              "id": "button",
              "offset": [0, 0, 0],
              "data": {
                "type": "button",
                "icon": {"type": "text", "text": "MENU_SHOW_PROBE"},
                "actions": [{"type": "command", "command": "show-confirmed"}]
              }
            }]
          }
          """.formatted(gate.equals("menu") ? condition : "",
          gate.equals("component") ? condition : "")).definition();
      holder.openSession(menu, null);
      MenuSession session = (MenuSession) CharacterizationSupport.getField(holder, "session");
      ButtonComponent button = (ButtonComponent) session.getComponents().getFirst();
      holder.tick();
      assertFalse(holder.isIdle());
      assertEquals(initialEntities, DisplayEntityManager.totalCount());
      assertEquals(initialVisible, DisplayEntityManager.visibleCount());
      assertEquals(0L, SENT.stream().filter(WrapperPlayServerSpawnEntity.class::isInstance).count());
      assertNull(holder.snapshotClick(eye.get()));
      button.onClick(HoloClickTrigger.RIGHT_CLICK);
      assertTrue(commands.isEmpty());

      time.set(6000L);
      holder.tick();
      assertEquals(initialEntities + 1, DisplayEntityManager.totalCount());
      assertEquals(initialVisible + 1, DisplayEntityManager.visibleCount());
      assertEquals(1L, SENT.stream().filter(WrapperPlayServerSpawnEntity.class::isInstance).count());
      Vector center = button.particlePlane().getCenter();
      eye.set(new Location(world, center.getX(), center.getY(), 0D));
      SessionHolder.ClickSnapshot click = holder.snapshotClick(eye.get());
      assertNotNull(click);
      click.component().onClick(HoloClickTrigger.RIGHT_CLICK);
      assertEquals(List.of("show-confirmed"), commands);

      SENT.clear();
      time.set(18000L);
      assertNull(holder.snapshotClick(eye.get()));
      click.component().onClick(HoloClickTrigger.RIGHT_CLICK);
      assertEquals(1, commands.size());
      holder.tick();
      assertFalse(holder.isIdle());
      assertEquals(initialEntities, DisplayEntityManager.totalCount());
      assertEquals(initialVisible, DisplayEntityManager.visibleCount());
      assertEquals(1L, SENT.stream().filter(WrapperPlayServerDestroyEntities.class::isInstance).count());
      assertEquals(0L, SENT.stream().filter(WrapperPlayServerSpawnEntity.class::isInstance).count());
      button.onClick(HoloClickTrigger.RIGHT_CLICK);
      assertEquals(1, commands.size());

      SENT.clear();
      holder.tick();
      assertTrue(SENT.isEmpty());
      time.set(6000L);
      holder.tick();
      assertEquals(initialEntities + 1, DisplayEntityManager.totalCount());
      assertEquals(initialVisible + 1, DisplayEntityManager.visibleCount());
      assertEquals(1L, SENT.stream().filter(WrapperPlayServerSpawnEntity.class::isInstance).count());
      SessionHolder.ClickSnapshot reopenedClick = holder.snapshotClick(eye.get());
      assertNotNull(reopenedClick);
      reopenedClick.component().onClick(HoloClickTrigger.RIGHT_CLICK);
      assertEquals(List.of("show-confirmed", "show-confirmed"), commands);
    } finally {
      try {
        holder.close(HoloCloseReason.GLOSS_SHUTDOWN);
      } finally {
        CharacterizationSupport.restoreGloss(previousPlugin);
        CharacterizationSupport.restoreServer(previousServer);
      }
    }
    assertEquals(initialEntities, DisplayEntityManager.totalCount());
    assertEquals(initialVisible, DisplayEntityManager.visibleCount());
  }

  @Test
  public void theScaleIndexCarriesTheScale() {
    DisplayEntity entity = textDisplay();
    entity.scale(new Vector3f(2F, 3F, 4F));

    EntityData<?> only = single(entity.metadataPacket(MetadataIndex.SCALE));

    assertEquals(SCALE, only.getIndex());
    assertEquals(new Vector3f(2F, 3F, 4F), only.getValue());
  }

  @Test
  public void theTransformSubsetCarriesTranslationThenScale() {
    DisplayEntity entity = textDisplay();
    entity.scale(new Vector3f(1F, 1F, 1F));
    entity.translation(new Vector3f(0.5F, -0.25F, 0F));

    List<EntityData<?>> values = entity.metadataPacket(MetadataIndex.TRANSLATION, MetadataIndex.SCALE)
        .getEntityMetadata();

    assertEquals(List.of(TRANSLATION, SCALE), values.stream().map(EntityData::getIndex).toList());
    assertEquals(new Vector3f(0.5F, -0.25F, 0F), values.get(0).getValue());
  }

  @Test
  public void theTextBackgroundIndexCarriesTheBackgroundColour() {
    DisplayEntity entity = textDisplay();
    entity.backgroundColor(0x40112233);

    EntityData<?> only = single(entity.metadataPacket(MetadataIndex.TEXT_BACKGROUND));

    assertEquals(TEXT_BACKGROUND, only.getIndex());
    assertEquals(0x40112233, only.getValue());
  }

  @Test
  public void theContentIndexCarriesTheText() {
    DisplayEntity entity = textDisplay();
    entity.text(Component.text("gloss"));

    assertEquals(Component.text("gloss"), single(entity.metadataPacket(MetadataIndex.CONTENT)).getValue());
  }

  @Test
  public void theLeftRotationIndexCarriesTheRoll() {
    DisplayEntity entity = textDisplay();
    Quaternion4f roll = new Quaternion4f(0F, 0F, 0.25F, 0.75F);
    entity.leftRotation(roll);

    assertEquals(roll, single(entity.metadataPacket(MetadataIndex.LEFT_ROTATION)).getValue());
  }

  // ---------------------------------------------------------------------
  // Manager behavior
  // ---------------------------------------------------------------------

  @Test
  public void orientingToTheSamePoseTwiceSendsNothingTheSecondTime() {
    Player viewer = viewer();
    UUID key = DisplayEntityManager.add(textDisplay());
    DisplayEntityManager.spawn(key, viewer);
    SENT.clear();

    DisplayEntityManager.orient(key, 90F, 15F, 30F);
    int afterFirst = SENT.size();
    assertTrue("the first orientation must actually be applied", afterFirst > 0);

    DisplayEntityManager.orient(key, 90F, 15F, 30F);

    assertEquals("an unchanged orientation must not put anything on the wire",
        afterFirst, SENT.size());

    DisplayEntityManager.delete(key, viewer);
  }

  @Test
  public void orientingNeverSendsAHeadLookAndRollTravelsAsOneMetadataEntry() {
    Player viewer = viewer();
    UUID key = DisplayEntityManager.add(textDisplay());
    DisplayEntityManager.spawn(key, viewer);
    SENT.clear();

    DisplayEntityManager.orient(key, 45F, 0F, 20F);

    List<WrapperPlayServerEntityMetadata> metadata = SENT.stream()
        .filter(WrapperPlayServerEntityMetadata.class::isInstance)
        .map(WrapperPlayServerEntityMetadata.class::cast)
        .toList();
    assertEquals(1, metadata.size());
    assertEquals("display entities ignore head yaw; only the roll needs a metadata entry",
        List.of(LEFT_ROTATION),
        metadata.getFirst().getEntityMetadata().stream().map(EntityData::getIndex).toList());
    assertTrue("no head-look packet may be sent", SENT.stream().noneMatch(
        p -> p.getClass().getSimpleName().contains("HeadLook")));

    DisplayEntityManager.delete(key, viewer);
  }

  @Test
  public void orientingALivingIconUpdatesItsBodyPitchAndHeadYaw() {
    Player viewer = viewer();
    DisplayEntity entity = DisplayEntity.Builder.entity(
        entityType(),
        new Location(null, 0D, 64D, 0D)
    );
    UUID key = DisplayEntityManager.add(entity);
    DisplayEntityManager.spawn(key, viewer);
    SENT.clear();

    DisplayEntityManager.orient(key, 75F, -20F, 30F);

    assertEquals(2, SENT.size());
    WrapperPlayServerEntityTeleport body = (WrapperPlayServerEntityTeleport) SENT.getFirst();
    WrapperPlayServerEntityHeadLook head = (WrapperPlayServerEntityHeadLook) SENT.getLast();
    assertEquals(75F, body.getYaw(), 0F);
    assertEquals(-20F, body.getPitch(), 0F);
    assertEquals(75F, head.getHeadYaw(), 0F);

    DisplayEntityManager.orient(key, 75F, -20F, 30F);
    assertEquals(2, SENT.size());
    DisplayEntityManager.delete(key, viewer);
  }

  @Test
  public void changingANameSendsOnlyTheContentEntry() {
    Player viewer = viewer();
    UUID key = DisplayEntityManager.add(textDisplay());
    DisplayEntityManager.spawn(key, viewer);
    SENT.clear();

    DisplayEntityManager.changeName(key, Component.text("renamed"));

    assertEquals(1, SENT.size());
    WrapperPlayServerEntityMetadata metadata = (WrapperPlayServerEntityMetadata) SENT.getFirst();
    assertEquals(List.of(CONTENT),
        metadata.getEntityMetadata().stream().map(EntityData::getIndex).toList());
    assertEquals(Component.text("renamed"), metadata.getEntityMetadata().getFirst().getValue());

    DisplayEntityManager.delete(key, viewer);
  }

  @Test
  public void deletingAnIconSendsOneDestroyPacketForEveryEntity() {
    Player viewer = viewer();
    DisplayEntity first = textDisplay();
    DisplayEntity second = textDisplay();
    DisplayEntity third = textDisplay();
    List<UUID> keys = List.of(
        DisplayEntityManager.add(first),
        DisplayEntityManager.add(second),
        DisplayEntityManager.add(third));
    keys.forEach(key -> DisplayEntityManager.spawn(key, viewer));
    int registered = DisplayEntityManager.totalCount();
    SENT.clear();

    DisplayEntityManager.deleteAll(keys, viewer);

    assertEquals("three entities must leave in one destroy packet", 1, SENT.size());
    assertArrayEquals(new int[]{first.id(), second.id(), third.id()},
        ((WrapperPlayServerDestroyEntities) SENT.getFirst()).getEntityIds());
    assertEquals("every handle must be dropped", registered - 3, DisplayEntityManager.totalCount());
  }

  @Test
  public void deletingALivingIconAlsoRemovesItsCollisionTeam() {
    Player viewer = viewer();
    DisplayEntity entity = DisplayEntity.Builder.entity(
        entityType(),
        new Location(null, 0D, 64D, 0D)
    );
    UUID key = DisplayEntityManager.add(entity);
    DisplayEntityManager.spawn(key, viewer);
    assertTrue(DisplayEntityManager.isVisibleRawEntity(viewer, entity.id()));
    SENT.clear();

    DisplayEntityManager.deleteAll(List.of(key), viewer);

    assertEquals(2, SENT.size());
    assertArrayEquals(new int[]{entity.id()},
        ((WrapperPlayServerDestroyEntities) SENT.getFirst()).getEntityIds());
    assertEquals(WrapperPlayServerTeams.TeamMode.REMOVE,
        ((WrapperPlayServerTeams) SENT.getLast()).getTeamMode());
    assertTrue(!DisplayEntityManager.isVisibleRawEntity(viewer, entity.id()));
  }

  @Test
  public void forgettingAPlayerDropsItsVisibilityBookkeeping() {
    Player viewer = viewer();
    UUID key = DisplayEntityManager.add(textDisplay());
    int before = DisplayEntityManager.visibleCount();
    DisplayEntityManager.spawn(key, viewer);
    assertEquals(before + 1, DisplayEntityManager.visibleCount());

    DisplayEntityManager.forget(viewer);

    assertEquals(before, DisplayEntityManager.visibleCount());
    DisplayEntityManager.delete(key, viewer);
  }

  // ---------------------------------------------------------------------
  // Plumbing
  // ---------------------------------------------------------------------

  private static EntityData<?> single(WrapperPlayServerEntityMetadata packet) {
    List<EntityData<?>> values = packet.getEntityMetadata();
    assertEquals(1, values.size());
    return values.getFirst();
  }

  private static DisplayEntity textDisplay() {
    return DisplayEntity.Builder.textDisplay(Component.text("x"), new Location(null, 0D, 64D, 0D));
  }

  private static EntityType entityType() {
    return (EntityType) Proxy.newProxyInstance(
        EntityType.class.getClassLoader(),
        new Class<?>[]{EntityType.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "EntityType[test]";
          default -> throw new UnsupportedOperationException(method.getName());
        }
    );
  }

  private static Player viewer() {
    UUID id = UUID.randomUUID();
    return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getUniqueId" -> id;
          case "getName" -> "packet-shape";
          default -> CharacterizationSupport.identity(proxy, method, args);
        });
  }

  private static final class RecordingPacketEventsApi extends PacketEventsAPI<Object> {
    private final PlayerManager playerManager = (PlayerManager) CharacterizationSupport.proxy(
        new Class<?>[]{PlayerManager.class},
        (proxy, method, args) -> {
          if ("sendPacket".equals(method.getName()) && args[1] instanceof PacketWrapper<?> packet) {
            SENT.add(packet);
            return null;
          }
          return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "PlayerManager[recording]";
            default -> null;
          };
        });

    @Override
    public boolean isLoaded() {
      return true;
    }

    @Override
    public void init() {
    }

    @Override
    public boolean isInitialized() {
      return true;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public Object getPlugin() {
      return this;
    }

    @Override
    public ServerManager getServerManager() {
      return () -> ServerVersion.V_26_1_2;
    }

    @Override
    public ProtocolManager getProtocolManager() {
      return null;
    }

    @Override
    public PlayerManager getPlayerManager() {
      return playerManager;
    }

    @Override
    public NettyManager getNettyManager() {
      return null;
    }

    @Override
    public ChannelInjector getInjector() {
      return null;
    }
  }
}
