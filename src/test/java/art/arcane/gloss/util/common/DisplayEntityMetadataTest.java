package art.arcane.gloss.util.common;

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
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class DisplayEntityMetadataTest {

  @BeforeClass
  public static void installPacketEventsApi() {
    PacketEvents.setAPI(new TestPacketEventsApi());
  }

  @AfterClass
  public static void clearPacketEventsApi() {
    PacketEvents.setAPI(null);
  }

  @Test
  public void rawEntitiesReceiveOnlyBaseMetadata() {
    EntityType entityType = entityType();
    DisplayEntity entity = DisplayEntity.Builder.entity(
        entityType,
        new Location(null, 1D, 2D, 3D, 35F, -10F)
    );
    WrapperPlayServerEntityMetadata metadata = (WrapperPlayServerEntityMetadata) entity.dataPacket();
    List<Integer> indexes = metadata.getEntityMetadata().stream().map(EntityData::getIndex).toList();

    assertEquals(List.of(0, 5), indexes);
  }

  @Test
  public void rawEntitySpawnCarriesBodyAndHeadOrientation() {
    EntityType entityType = entityType();
    DisplayEntity entity = DisplayEntity.Builder.entity(
        entityType,
        new Location(null, 1D, 2D, 3D, 35F, -10F)
    );
    List<PacketWrapper<?>> packets = entity.spawn();
    WrapperPlayServerTeams team = (WrapperPlayServerTeams) packets.getFirst();
    WrapperPlayServerSpawnEntity spawn = (WrapperPlayServerSpawnEntity) packets.get(1);

    assertEquals(WrapperPlayServerTeams.TeamMode.CREATE, team.getTeamMode());
    assertEquals(WrapperPlayServerTeams.CollisionRule.NEVER,
        team.getTeamInfo().orElseThrow().getCollisionRule());
    assertEquals(List.of(entity.uuid().toString()), team.getPlayers());
    assertEquals(35F, spawn.getYaw(), 0F);
    assertEquals(35F, spawn.getHeadYaw(), 0F);
    assertEquals(-10F, spawn.getPitch(), 0F);
    assertSame(entityType, spawn.getEntityType());
  }

  @Test
  public void rawEntityMovementOrientationAndDespawnStayPacketOnly() {
    DisplayEntity entity = DisplayEntity.Builder.entity(
        entityType(),
        new Location(null, 1D, 2D, 3D)
    );
    WrapperPlayServerEntityTeleport moved = (WrapperPlayServerEntityTeleport) entity.move(new Vector(2D, -1D, 4D));
    WrapperPlayServerEntityTeleport rotated = (WrapperPlayServerEntityTeleport) entity.rotate(70F, 15F);
    WrapperPlayServerEntityHeadLook headLook = (WrapperPlayServerEntityHeadLook) entity.headLook();
    WrapperPlayServerDestroyEntities removed = (WrapperPlayServerDestroyEntities) entity.remove();
    WrapperPlayServerTeams teamRemoved = entity.collisionTeamRemove();

    assertEquals(3D, moved.getPosition().getX(), 0D);
    assertEquals(1D, moved.getPosition().getY(), 0D);
    assertEquals(7D, moved.getPosition().getZ(), 0D);
    assertEquals(70F, rotated.getYaw(), 0F);
    assertEquals(15F, rotated.getPitch(), 0F);
    assertEquals(70F, headLook.getHeadYaw(), 0F);
    assertArrayEquals(new int[]{entity.id()}, removed.getEntityIds());
    assertEquals(WrapperPlayServerTeams.TeamMode.REMOVE, teamRemoved.getTeamMode());
  }

  @Test
  public void displayEntitiesRetainDisplayMetadata() {
    DisplayEntity entity = new DisplayEntity(1, UUID.randomUUID(), entityType())
        .displayKind(DisplayEntity.DisplayKind.TEXT)
        .text(Component.text("A"));
    WrapperPlayServerEntityMetadata metadata = (WrapperPlayServerEntityMetadata) entity.dataPacket();
    List<Integer> indexes = metadata.getEntityMetadata().stream().map(EntityData::getIndex).toList();

    assertEquals(List.of(0, 5, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27), indexes);
  }

  @Test
  public void blockDisplaysCarryTheirGlobalBlockStateAtIndexTwentyThree() {
    DisplayEntity entity = new DisplayEntity(1, UUID.randomUUID(), entityType())
        .displayKind(DisplayEntity.DisplayKind.BLOCK)
        .blockState(9812);
    WrapperPlayServerEntityMetadata metadata = (WrapperPlayServerEntityMetadata) entity.dataPacket();
    List<EntityData<?>> values = metadata.getEntityMetadata();

    assertEquals(List.of(0, 5, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23),
        values.stream().map(EntityData::getIndex).toList());
    assertEquals(9812, values.getLast().getValue());
  }

  @Test
  public void textDisplaySubsetsMatchTheFullDataPacketEntries() {
    DisplayEntity entity = new DisplayEntity(1, UUID.randomUUID(), entityType())
        .displayKind(DisplayEntity.DisplayKind.TEXT)
        .text(Component.text("A"))
        .lineWidth(150)
        .backgroundColor(7)
        .textOpacity((byte) 12)
        .textFlags((byte) 3)
        .billboard((byte) 1)
        .brightness(9)
        .viewRange(3.5F)
        .glowColorOverride(4);

    assertSubsetsMatchFull(entity);
  }

  @Test
  public void blockDisplaySubsetsMatchTheFullDataPacketEntries() {
    DisplayEntity entity = new DisplayEntity(2, UUID.randomUUID(), entityType())
        .displayKind(DisplayEntity.DisplayKind.BLOCK)
        .blockState(9812);

    assertSubsetsMatchFull(entity);
  }

  @Test
  public void rawEntitySubsetsMatchTheFullDataPacketEntries() {
    DisplayEntity entity = DisplayEntity.Builder.entity(
        entityType(),
        new Location(null, 1D, 2D, 3D)
    );

    assertSubsetsMatchFull(entity);
  }

  @Test
  public void itemDisplaySubsetCarriesTheItemDisplayType() {
    DisplayEntity entity = new DisplayEntity(3, UUID.randomUUID(), entityType())
        .displayKind(DisplayEntity.DisplayKind.ITEM)
        .itemDisplayType((byte) 5);
    WrapperPlayServerEntityMetadata subset = entity.metadataPacket(24);
    List<EntityData<?>> values = subset.getEntityMetadata();

    assertEquals(1, values.size());
    assertEquals(24, values.getFirst().getIndex());
    assertEquals((byte) 5, values.getFirst().getValue());
  }

  @Test
  public void aMultiIndexSubsetPreservesRequestOrder() {
    DisplayEntity entity = new DisplayEntity(4, UUID.randomUUID(), entityType())
        .displayKind(DisplayEntity.DisplayKind.TEXT)
        .text(Component.text("A"));
    WrapperPlayServerEntityMetadata subset = entity.metadataPacket(13, 12, 23);

    assertEquals(List.of(13, 12, 23),
        subset.getEntityMetadata().stream().map(EntityData::getIndex).toList());
  }

  @Test
  public void inapplicableIndicesAreOmittedFromSubsets() {
    DisplayEntity raw = DisplayEntity.Builder.entity(
        entityType(),
        new Location(null, 1D, 2D, 3D)
    );
    DisplayEntity block = new DisplayEntity(5, UUID.randomUUID(), entityType())
        .displayKind(DisplayEntity.DisplayKind.BLOCK)
        .blockState(11);

    assertTrue(raw.metadataPacket(8, 23, 24).getEntityMetadata().isEmpty());
    assertEquals(List.of(23),
        block.metadataPacket(24, 25, 26, 27, 23, 99).getEntityMetadata().stream().map(EntityData::getIndex).toList());
  }

  @Test
  public void destroyAllBatchesEveryIdIntoOnePacket() {
    WrapperPlayServerDestroyEntities packet = DisplayEntity.destroyAll(new int[]{7, 9, 11});

    assertArrayEquals(new int[]{7, 9, 11}, packet.getEntityIds());
  }

  private static void assertSubsetsMatchFull(DisplayEntity entity) {
    WrapperPlayServerEntityMetadata full = (WrapperPlayServerEntityMetadata) entity.dataPacket();
    for (EntityData<?> expected : full.getEntityMetadata()) {
      WrapperPlayServerEntityMetadata subset = entity.metadataPacket(expected.getIndex());
      List<EntityData<?>> values = subset.getEntityMetadata();

      assertEquals(1, values.size());
      EntityData<?> actual = values.getFirst();
      assertEquals(expected.getIndex(), actual.getIndex());
      assertSame(expected.getType(), actual.getType());
      assertEquals(expected.getValue(), actual.getValue());
    }
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

  private static final class TestPacketEventsApi extends PacketEventsAPI<Object> {
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
      return null;
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
