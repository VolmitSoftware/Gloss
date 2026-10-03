package art.arcane.gloss.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HoloCloseReason;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.packets.PacketEventsStub;
import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.volmlib.util.bukkit.papi.PlayerSnapshotStore;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class MenuTeleportLifecycleTest {
  private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000007e1e");

  private Gloss previousGloss;
  private World world;
  private AtomicReference<Location> location;
  private Player player;
  private SessionHolder holder;

  @Before
  public void setUp() throws ReflectiveOperationException {
    PacketEventsStub.install();
    previousGloss = CharacterizationSupport.installGloss(
        CharacterizationSupport.bareGloss(CharacterizationSupport.server(Map.of())));
    world = (World) CharacterizationSupport.proxy(new Class<?>[]{World.class},
        CharacterizationSupport::identity);
    location = new AtomicReference<>(new Location(world, 400.5D, 81D, 400.5D));
    player = (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getUniqueId" -> PLAYER_ID;
          case "getName" -> "GardenGuide";
          case "isOnline" -> true;
          case "getLocation" -> location.get().clone();
          case "getEyeLocation" -> location.get().clone().add(0D, 1.62D, 0D);
          default -> CharacterizationSupport.identity(proxy, method, args);
        });
    holder = new SessionHolder(player, new PlayerSnapshotStore<>());
  }

  @After
  public void tearDown() {
    holder.close(HoloCloseReason.GLOSS_SHUTDOWN);
    CharacterizationSupport.restoreGloss(previousGloss);
    PacketEventsStub.uninstall();
    GlossTelemetry.clear();
  }

  @Test
  public void correctionToAcceptedPositionIgnoresChangedRotation() {
    holder.openSession(menu("close", true, false), null, Map.of());
    holder.recordAcceptedPosition(new Location(world, 401.5D, 81D, 400.5D));

    assertNull(holder.captureServerTeleport(packet(401.5D, 81D, 400.5D, new RelativeFlag(0))));
    assertTrue(holder.hasSession());
  }

  @Test
  public void correctionWithinNativeMoveEventThresholdDoesNotClose() {
    holder.openSession(menu("close", true, false), null, Map.of());

    assertNull(holder.captureServerTeleport(packet(400.55D, 81D, 400.5D, new RelativeFlag(0))));
    assertTrue(holder.hasSession());
  }

  @Test
  public void actualCommandDisplacementClosesCapturedSession() {
    holder.openSession(menu("close", true, false), null, Map.of());
    SessionHolder.ServerTeleport signal = holder.captureServerTeleport(
        packet(410.5D, 81D, 400.5D, new RelativeFlag(0)));
    assertNotNull(signal);
    location.set(new Location(world, 410.5D, 81D, 400.5D));

    holder.applyServerTeleport(signal);

    assertFalse(holder.hasSession());
    assertNull(holder.captureServerTeleport(packet(420.5D, 81D, 400.5D, new RelativeFlag(0))));
  }

  @Test
  public void queuedTeleportCannotCloseReplacementSession() {
    holder.openSession(menu("old", true, false), null, Map.of());
    SessionHolder.ServerTeleport signal = holder.captureServerTeleport(
        packet(410.5D, 81D, 400.5D, new RelativeFlag(0)));
    location.set(new Location(world, 410.5D, 81D, 400.5D));
    holder.openSession(menu("new", true, false), null, Map.of());
    AtomicReference<MenuSession> replacement = new AtomicReference<>();
    holder.onSession(replacement::set);

    holder.applyServerTeleport(signal);

    holder.onSession(current -> assertSame(replacement.get(), current));
    assertNull(holder.captureServerTeleport(packet(410.5D, 81D, 400.5D, new RelativeFlag(0))));
  }

  @Test
  public void relativeCorrectionAndRelativeDisplacementUseAcceptedPosition() {
    holder.openSession(menu("close", true, false), null, Map.of());
    RelativeFlag flags = RelativeFlag.X.or(RelativeFlag.Y).or(RelativeFlag.Z);
    assertNull(holder.captureServerTeleport(packet(0D, 0D, 0D, flags)));
    assertNotNull(holder.captureServerTeleport(packet(10D, 0D, 0D, flags)));
  }

  @Test
  public void retainedFixedMenuUsesActualArrivalRatherThanPacketCoordinates() {
    holder.openSession(menu("fixed", false, false), null, Map.of());
    SessionHolder.ServerTeleport signal = holder.captureServerTeleport(
        packet(404.5D, 81D, 400.5D, new RelativeFlag(0)));
    location.set(new Location(world, 403.5D, 81D, 400.5D));

    holder.applyServerTeleport(signal);

    assertTrue(holder.hasSession());
    holder.onSession(current -> assertEquals(403.5D, current.getCenterPoint().getX(), 0D));
  }

  @Test
  public void eventThenRelativePacketDoesNotApplyRetainedFollowingMenuOffsetTwice() {
    holder.openSession(menu("follow", false, true), null, Map.of());
    Location destination = new Location(world, 404.5D, 81D, 400.5D, 90F, 0F);
    holder.inspectSession(current -> holder.applyTeleport(current, destination));
    SessionHolder.ServerTeleport signal = holder.captureServerTeleport(
        packet(4D, 0D, 0D, RelativeFlag.X.or(RelativeFlag.Y).or(RelativeFlag.Z)));
    assertNotNull(signal);
    location.set(destination);

    holder.applyServerTeleport(signal);

    assertTrue(holder.hasSession());
    holder.onSession(current -> assertEquals(404.5D, current.getCenterPoint().getX(), 0D));
  }

  @Test
  public void cancelledBukkitTeleportLeavesCloseOnTeleportMenuOpen() {
    MenuSessionManager manager = new MenuSessionManager();
    try {
      manager.createNewSession(player, menu("close", true, false));
      PlayerTeleportEvent event = new PlayerTeleportEvent(player, location.get().clone(),
          new Location(world, 410.5D, 81D, 400.5D), PlayerTeleportEvent.TeleportCause.PLUGIN);
      event.setCancelled(true);

      manager.dispatchTeleport(event);

      assertTrue(manager.hasMenuSession(player));
    } finally {
      manager.destroyAll();
    }
  }

  private static MenuDefinitionData menu(String id, boolean closeOnTeleport, boolean follow) {
    MenuDefinitionData data = new MenuDefinitionData(new Vector(), false, follow, 32D,
        false, closeOnTeleport, List.of(), List.of(), ShowCondition.ALWAYS, Map.of(), List.of());
    data.setId(id);
    return data;
  }

  private static WrapperPlayServerPlayerPositionAndLook packet(double x, double y, double z, RelativeFlag flags) {
    return new WrapperPlayServerPlayerPositionAndLook(1, new Vector3d(x, y, z), Vector3d.zero(),
        180F, 45F, flags);
  }
}
