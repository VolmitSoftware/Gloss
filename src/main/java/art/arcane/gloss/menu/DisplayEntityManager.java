package art.arcane.gloss.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.gloss.util.common.DisplayEntity.MetadataIndex;
import art.arcane.gloss.util.common.PacketUtils;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public class DisplayEntityManager {

  /**
   * High half of every manager key. The keys are internal handles only — the uuid a client sees is
   * the one {@link DisplayEntity} carries — so a counter is used instead of
   * {@link UUID#randomUUID()} and its {@code SecureRandom} draw.
   */
  private static final long KEY_NAMESPACE = 0x676C6F73734D4E55L;

  private static final Map<UUID, DisplayEntity> displayEntities = new ConcurrentHashMap<>();
  private static final Map<UUID, Player> playerVisibility = new ConcurrentHashMap<>();
  /**
   * Reverse of {@link #playerVisibility}. A quit has to drop one player's handles, and without this
   * it would compare every handle on the server to do it — a mass disconnect turns that into a
   * quadratic sweep on the main thread inside a single tick.
   */
  private static final Map<UUID, Set<UUID>> handlesByViewer = new ConcurrentHashMap<>();
  private static final Map<Integer, UUID> rawEntityIds = new ConcurrentHashMap<>();
  private static final AtomicBoolean unsupportedVersionWarning = new AtomicBoolean(false);
  private static final AtomicLong keySequence = new AtomicLong();

  /** Null until PacketEvents is up; the server version cannot change afterwards. */
  private static volatile Boolean versionSupported;

  public static UUID add(DisplayEntity displayEntity) {
    UUID uuid = new UUID(KEY_NAMESPACE, keySequence.incrementAndGet());
    displayEntities.put(uuid, displayEntity);
    if (displayEntity.isRawEntity()) {
      rawEntityIds.put(displayEntity.id(), uuid);
    }
    return uuid;
  }

  public static int totalCount() {
    return displayEntities.size();
  }

  public static int visibleCount() {
    return playerVisibility.size();
  }

  public static void spawn(UUID uuid, Player player) {
    if (unsupportedVersion())
      return;
    DisplayEntity displayEntity = displayEntities.get(uuid);
    if (displayEntity == null || player == null)
      return;

    PacketUtils.send(player, displayEntity.spawn());
    bind(uuid, player);
    GlossTelemetry.countSpawnChurn();
  }

  public static void despawn(UUID uuid) {
    if (unsupportedVersion()) {
      unbind(uuid);
      return;
    }
    DisplayEntity displayEntity = displayEntities.get(uuid);
    Player player = unbind(uuid);
    if (displayEntity == null || player == null)
      return;
    PacketUtils.send(player, removalPackets(displayEntity));
    GlossTelemetry.countSpawnChurn();
  }

  public static void delete(UUID uuid) {
    if (!displayEntities.containsKey(uuid))
      return;

    despawn(uuid);
    forgetEntity(displayEntities.remove(uuid));
  }

  public static void delete(UUID uuid, Player fallbackPlayer) {
    if (!displayEntities.containsKey(uuid))
      return;

    if (unsupportedVersion()) {
      forgetEntity(displayEntities.remove(uuid));
      unbind(uuid);
      return;
    }

    DisplayEntity displayEntity = displayEntities.get(uuid);
    Player player = unbind(uuid);
    Player target = player == null ? fallbackPlayer : player;
    if (displayEntity != null && target != null) {
      PacketUtils.send(target, removalPackets(displayEntity));
    }
    forgetEntity(displayEntities.remove(uuid));
  }

  /**
   * Deletes a whole icon's worth of handles, collapsing the removals into one destroy packet per
   * receiving player instead of one per entity. Same bookkeeping as
   * {@link #delete(UUID, Player)}, which it replaces at the icon teardown call sites.
   */
  public static void deleteAll(List<UUID> uuids, Player fallbackPlayer) {
    if (uuids == null || uuids.isEmpty())
      return;

    boolean unsupported = unsupportedVersion();
    Map<Player, List<DisplayEntity>> byViewer = unsupported ? null : new IdentityHashMap<>(2);
    for (UUID uuid : uuids) {
      DisplayEntity displayEntity = displayEntities.remove(uuid);
      forgetEntity(displayEntity);
      Player player = unbind(uuid);
      if (unsupported || displayEntity == null)
        continue;
      Player target = player == null ? fallbackPlayer : player;
      if (target == null)
        continue;
      byViewer.computeIfAbsent(target, ignored -> new ArrayList<>(uuids.size()))
          .add(displayEntity);
    }

    if (unsupported)
      return;

    for (Map.Entry<Player, List<DisplayEntity>> entry : byViewer.entrySet()) {
      List<DisplayEntity> entities = entry.getValue();
      int[] packed = new int[entities.size()];
      for (int index = 0; index < packed.length; index++) {
        packed[index] = entities.get(index).id();
      }
      List<PacketWrapper<?>> packets = new ArrayList<>(entities.size() + 1);
      packets.add(DisplayEntity.destroyAll(packed));
      for (DisplayEntity displayEntity : entities) {
        if (displayEntity.isRawEntity()) {
          packets.add(displayEntity.collisionTeamRemove());
        }
      }
      PacketUtils.send(entry.getKey(), packets);
    }
  }

  /**
   * Drops a departed player's visibility bookkeeping. Menu teardown normally deletes the handles
   * first; this is the sweep for anything that outlived its session. It costs that player's own
   * handles, not every handle on the server, which is what keeps a mass disconnect off the tick.
   */
  public static void forget(Player player) {
    if (player == null)
      return;
    Set<UUID> handles = handlesByViewer.remove(player.getUniqueId());
    if (handles == null)
      return;
    for (UUID handle : handles) {
      playerVisibility.remove(handle);
    }
  }

  private static void bind(UUID uuid, Player player) {
    Player previous = playerVisibility.put(uuid, player);
    if (previous != null && !previous.getUniqueId().equals(player.getUniqueId())) {
      detach(previous.getUniqueId(), uuid);
    }
    handlesByViewer.computeIfAbsent(player.getUniqueId(), id -> ConcurrentHashMap.newKeySet()).add(uuid);
  }

  private static Player unbind(UUID uuid) {
    Player previous = playerVisibility.remove(uuid);
    if (previous != null) {
      detach(previous.getUniqueId(), uuid);
    }
    return previous;
  }

  private static void detach(UUID playerId, UUID handle) {
    handlesByViewer.computeIfPresent(playerId, (id, handles) -> {
      handles.remove(handle);
      return handles.isEmpty() ? null : handles;
    });
  }

  public static boolean isVisibleRawEntity(Player player, int entityId) {
    if (player == null) {
      return false;
    }
    UUID handle = rawEntityIds.get(entityId);
    Player viewer = handle == null ? null : playerVisibility.get(handle);
    return viewer != null && viewer.getUniqueId().equals(player.getUniqueId());
  }

  private static void forgetEntity(DisplayEntity displayEntity) {
    if (displayEntity != null && displayEntity.isRawEntity()) {
      rawEntityIds.remove(displayEntity.id());
    }
  }

  public static Vector location(UUID uuid) {
    DisplayEntity displayEntity = displayEntities.get(uuid);
    if (displayEntity == null)
      return new Vector();

    return PacketUtils.vector(displayEntity.location());
  }

  public static void goTo(UUID uuid, Location location) {
    PacketWrapper<?> teleport = goToPacket(uuid, location);
    if (teleport == null)
      return;
    PacketUtils.sendOne(playerVisibility.get(uuid), teleport);
  }

  /**
   * The teleport {@link #goTo} would have sent, moved into the entity's state but left unsent, or
   * null when there is nothing to send. Callers that move many displays belonging to one viewer in
   * the same tick collect these and hand the whole list to
   * {@link PacketUtils#send(Player, java.util.Collection)} instead of paying a send call per entity.
   *
   * <p>Nothing is sent when the entity is already exactly where it is being sent, mirroring the
   * elision {@link #orient} makes. Callers that re-assert a fixed offset every tick — a block
   * icon's vertical correction, most of all — would otherwise ship a teleport per icon per tick
   * that moves nothing.
   */
  public static PacketWrapper<?> goToPacket(UUID uuid, Location location) {
    if (unsupportedVersion())
      return null;
    DisplayEntity displayEntity = displayEntities.get(uuid);
    Player player = playerVisibility.get(uuid);
    if (displayEntity == null || player == null)
      return null;
    if (isAlreadyAt(displayEntity, location))
      return null;
    return displayEntity.goTo(location);
  }

  private static boolean isAlreadyAt(DisplayEntity displayEntity, Location location) {
    Vector3d current = displayEntity.location();
    return current != null
        && current.getX() == location.getX()
        && current.getY() == location.getY()
        && current.getZ() == location.getZ()
        && displayEntity.yaw() == location.getYaw()
        && displayEntity.pitch() == location.getPitch()
        && displayEntity.headYaw() == location.getYaw();
  }

  public static void move(UUID uuid, Vector offset) {
    if (unsupportedVersion())
      return;
    DisplayEntity displayEntity = displayEntities.get(uuid);
    Player player = playerVisibility.get(uuid);
    if (displayEntity == null || player == null)
      return;
    PacketUtils.sendOne(player, displayEntity.move(offset));
  }

  /**
   * Points an icon at the menu's facing. Living icons receive body and head rotation packets;
   * display entities receive roll as one left-rotation metadata entry. Nothing is sent when the
   * stored orientation already matches, but the state is written before spawn in both cases.
   */
  public static void orient(UUID uuid, float yaw, float pitch, float roll) {
    double halfRoll = Math.toRadians(roll) / 2.0D;
    orient(uuid, yaw, pitch, new Quaternion4f(0F, 0F, (float) Math.sin(halfRoll), (float) Math.cos(halfRoll)));
  }

  public static void orient(UUID uuid, float yaw, float pitch, Quaternion4f rotation) {
    List<PacketWrapper<?>> packets = orientPackets(uuid, yaw, pitch, rotation);
    if (packets.isEmpty())
      return;
    Player player = playerVisibility.get(uuid);
    for (PacketWrapper<?> packet : packets) {
      PacketUtils.sendOne(player, packet);
    }
  }

  /**
   * The packets {@link #orient} would have sent, with the entity's state already advanced and
   * nothing sent: empty when facing and rotation are unchanged or the display has no viewer. Box
   * decorations collect these with their teleports and transforms so a multi-part update leaves
   * in one flush instead of one per packet.
   */
  public static List<PacketWrapper<?>> orientPackets(UUID uuid, float yaw, float pitch, Quaternion4f rotation) {
    if (unsupportedVersion())
      return List.of();
    DisplayEntity displayEntity = displayEntities.get(uuid);
    if (displayEntity == null)
      return List.of();
    Player player = playerVisibility.get(uuid);
    boolean facingUnchanged = displayEntity.yaw() == yaw && displayEntity.pitch() == pitch;
    if (displayEntity.isRawEntity()) {
      boolean headUnchanged = displayEntity.headYaw() == yaw;
      displayEntity.yaw(yaw).pitch(pitch).headYaw(yaw);
      if (player == null || (facingUnchanged && headUnchanged))
        return List.of();
      List<PacketWrapper<?>> packets = new ArrayList<>(2);
      if (!facingUnchanged)
        packets.add(displayEntity.rotate(yaw, pitch));
      if (!headUnchanged)
        packets.add(displayEntity.headLook());
      return packets;
    }

    Quaternion4f previous = displayEntity.leftRotation();
    boolean rotationUnchanged = previous != null && previous.getX() == rotation.getX()
        && previous.getY() == rotation.getY() && previous.getZ() == rotation.getZ()
        && previous.getW() == rotation.getW();
    displayEntity.yaw(yaw)
        .pitch(pitch)
        .leftRotation(rotation);
    if (player == null || (facingUnchanged && rotationUnchanged))
      return List.of();
    List<PacketWrapper<?>> packets = new ArrayList<>(2);
    if (!facingUnchanged)
      packets.add(displayEntity.rotate(yaw, pitch));
    if (!rotationUnchanged)
      packets.add(displayEntity.metadataPacket(MetadataIndex.LEFT_ROTATION));
    return packets;
  }

  private static List<PacketWrapper<?>> removalPackets(DisplayEntity displayEntity) {
    if (!displayEntity.isRawEntity()) {
      return List.of(displayEntity.remove());
    }
    return List.of(displayEntity.remove(), displayEntity.collisionTeamRemove());
  }

  public static void changeName(UUID uuid, Component name) {
    if (unsupportedVersion())
      return;
    DisplayEntity displayEntity = displayEntities.get(uuid);
    Player player = playerVisibility.get(uuid);
    if (displayEntity == null || player == null)
      return;
    if (!displayEntity.isTextDisplay())
      return;
    Component text = name == null ? Component.empty() : name;
    displayEntity.text(text);
    PacketUtils.sendOne(player, DisplayEntity.textUpdate(displayEntity.id(), text));
  }

  public static void changeNames(List<UUID> uuids, List<Component> names) {
    if (unsupportedVersion() || uuids == null || names == null || uuids.size() != names.size()) {
      return;
    }
    Map<Player, List<PacketWrapper<?>>> byViewer = new IdentityHashMap<>(2);
    for (int index = 0; index < uuids.size(); index++) {
      UUID uuid = uuids.get(index);
      DisplayEntity displayEntity = displayEntities.get(uuid);
      Player player = playerVisibility.get(uuid);
      if (displayEntity == null || player == null || !displayEntity.isTextDisplay()) {
        continue;
      }
      Component text = names.get(index) == null ? Component.empty() : names.get(index);
      if (text.equals(displayEntity.text())) {
        continue;
      }
      displayEntity.text(text);
      byViewer.computeIfAbsent(player, ignored -> new ArrayList<>(uuids.size()))
          .add(DisplayEntity.textUpdate(displayEntity.id(), text));
    }
    for (Map.Entry<Player, List<PacketWrapper<?>>> entry : byViewer.entrySet()) {
      PacketUtils.send(entry.getKey(), entry.getValue());
    }
  }

  public static void changeTextBackground(UUID uuid, int backgroundColor) {
    PacketWrapper<?> packet = changeTextBackgroundPacket(uuid, backgroundColor);
    if (packet != null)
      PacketUtils.sendOne(playerVisibility.get(uuid), packet);
  }

  /** The packet {@link #changeTextBackground} would have sent, state advanced, nothing sent. */
  public static PacketWrapper<?> changeTextBackgroundPacket(UUID uuid, int backgroundColor) {
    if (unsupportedVersion())
      return null;
    DisplayEntity displayEntity = displayEntities.get(uuid);
    Player player = playerVisibility.get(uuid);
    if (displayEntity == null || player == null)
      return null;
    if (!displayEntity.isTextDisplay())
      return null;
    displayEntity.backgroundColor(backgroundColor);
    return displayEntity.metadataPacket(MetadataIndex.TEXT_BACKGROUND);
  }

  public static void changeScale(UUID uuid, float x, float y, float z) {
    if (unsupportedVersion())
      return;
    DisplayEntity displayEntity = displayEntities.get(uuid);
    Player player = playerVisibility.get(uuid);
    if (displayEntity == null || player == null)
      return;
    displayEntity.scale(new Vector3f(x, y, z));
    PacketUtils.sendOne(player, displayEntity.metadataPacket(MetadataIndex.SCALE));
  }

  public static void changeTransform(UUID uuid, float x, float y, float z, Vector3f translation) {
    PacketWrapper<?> packet = changeTransformPacket(uuid, x, y, z, translation);
    if (packet != null)
      PacketUtils.sendOne(playerVisibility.get(uuid), packet);
  }

  /** The packet {@link #changeTransform} would have sent, state advanced, nothing sent. */
  public static PacketWrapper<?> changeTransformPacket(UUID uuid, float x, float y, float z, Vector3f translation) {
    if (unsupportedVersion())
      return null;
    DisplayEntity displayEntity = displayEntities.get(uuid);
    Player player = playerVisibility.get(uuid);
    if (displayEntity == null || player == null)
      return null;
    displayEntity.scale(new Vector3f(x, y, z));
    displayEntity.translation(translation == null ? new Vector3f(0, 0, 0) : translation);
    return displayEntity.metadataPacket(MetadataIndex.TRANSLATION, MetadataIndex.SCALE);
  }

  public static void changeItem(UUID uuid, ItemStack itemStack) {
    if (unsupportedVersion())
      return;
    DisplayEntity displayEntity = displayEntities.get(uuid);
    Player player = playerVisibility.get(uuid);
    if (displayEntity == null || player == null)
      return;
    if (!displayEntity.isItemDisplay())
      return;
    displayEntity.item(itemStack == null ? new ItemStack(Material.AIR) : itemStack.clone());
    PacketUtils.sendOne(player, displayEntity.metadataPacket(MetadataIndex.CONTENT));
  }

  private static boolean unsupportedVersion() {
    Boolean resolved = versionSupported;
    if (resolved != null) {
      return !resolved;
    }

    PacketEventsAPI<?> api = PacketEvents.getAPI();
    if (api != null) {
      boolean supported = api.getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_19_4);
      versionSupported = supported;
      if (supported) {
        return false;
      }
    }

    if (unsupportedVersionWarning.compareAndSet(false, true)) {
      Gloss.log(Level.WARNING, "HoloUi display-entity renderer requires Minecraft 1.19.4 or newer.");
    }

    return true;
  }
}
