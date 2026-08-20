package art.arcane.gloss.menu;

import art.arcane.gloss.util.common.DisplayEntity;
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
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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
  public void theScaleIndexCarriesTheScale() {
    DisplayEntity entity = textDisplay();
    entity.scale(new Vector3f(2F, 3F, 4F));

    EntityData<?> only = single(entity.metadataPacket(SCALE));

    assertEquals(SCALE, only.getIndex());
    assertEquals(new Vector3f(2F, 3F, 4F), only.getValue());
  }

  @Test
  public void theTransformSubsetCarriesTranslationThenScale() {
    DisplayEntity entity = textDisplay();
    entity.scale(new Vector3f(1F, 1F, 1F));
    entity.translation(new Vector3f(0.5F, -0.25F, 0F));

    List<EntityData<?>> values = entity.metadataPacket(TRANSLATION, SCALE).getEntityMetadata();

    assertEquals(List.of(TRANSLATION, SCALE), values.stream().map(EntityData::getIndex).toList());
    assertEquals(new Vector3f(0.5F, -0.25F, 0F), values.get(0).getValue());
  }

  @Test
  public void theTextBackgroundIndexCarriesTheBackgroundColour() {
    DisplayEntity entity = textDisplay();
    entity.backgroundColor(0x40112233);

    assertEquals(0x40112233, single(entity.metadataPacket(TEXT_BACKGROUND)).getValue());
  }

  @Test
  public void theContentIndexCarriesTheText() {
    DisplayEntity entity = textDisplay();
    entity.text(Component.text("gloss"));

    assertEquals(Component.text("gloss"), single(entity.metadataPacket(CONTENT)).getValue());
  }

  @Test
  public void theLeftRotationIndexCarriesTheRoll() {
    DisplayEntity entity = textDisplay();
    Quaternion4f roll = new Quaternion4f(0F, 0F, 0.25F, 0.75F);
    entity.leftRotation(roll);

    assertEquals(roll, single(entity.metadataPacket(LEFT_ROTATION)).getValue());
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
