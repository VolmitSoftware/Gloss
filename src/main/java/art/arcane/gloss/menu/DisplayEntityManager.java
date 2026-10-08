package art.arcane.gloss.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.gloss.service.VisibilityGovernor;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
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
  private static final Map<UUID, DisplayEntityGroup> fallbackGroups = new ConcurrentHashMap<>();
  private static final Map<DisplayEntityGroup, Retirement> retirements = new ConcurrentHashMap<>();
  private static final Map<UUID, DisplayEntityGroup> entityGroups = new ConcurrentHashMap<>();
  private static final Map<DisplayEntityGroup, Set<UUID>> handlesByGroup = new ConcurrentHashMap<>();
  private static final Map<UUID, Set<DisplayEntityGroup>> groupsByViewer = new ConcurrentHashMap<>();
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

  public static UUID add(DisplayEntityGroup group, DisplayEntity displayEntity) {
    UUID handle = add(displayEntity);
    attachGroup(group, handle);
    return handle;
  }

  public static DisplayEntityGroup group(Player viewer, VisibilityGovernor.Surface surface) {
    return new DisplayEntityGroup(new DisplayEntityGroup.Options(viewer, surface,
        DisplayEntityManager::governor,
        new PacketTransport(viewer)));
  }

  public static void retire(Gloss plugin, DisplayEntityGroup group) {
    Retirement candidate = new Retirement(plugin, new AtomicBoolean());
    Retirement previous = retirements.putIfAbsent(group, candidate);
    dispatchRetirement(group, previous == null ? candidate : previous);
  }

  public static int pendingRetirements() {
    return retirements.size();
  }

  public static void pumpRetirements() {
    for (Map.Entry<DisplayEntityGroup, Retirement> entry : retirements.entrySet()) {
      dispatchRetirement(entry.getKey(), entry.getValue());
    }
  }

  private static void dispatchRetirement(DisplayEntityGroup group, Retirement retirement) {
    Gloss plugin = retirement.plugin();
    AtomicBoolean scheduled = retirement.scheduled();
    if (plugin == null || !scheduled.compareAndSet(false, true)) {
      return;
    }
    boolean accepted = FoliaScheduler.runEntity(plugin, group.viewer(), () -> {
      try {
        group.close();
        retirements.remove(group, retirement);
      } catch (RuntimeException failure) {
        Gloss.logExceptionStackThrottled(false, "display-retirement", failure,
            "Cannot retire a display group for %s; retaining its reservation for retry.", group.viewer().getUniqueId());
      } finally {
        scheduled.set(false);
      }
    }, 0L, () -> {
      if (!group.viewer().isOnline()) {
        group.disconnected();
        retirements.remove(group, retirement);
      }
      scheduled.set(false);
    });
    if (!accepted) {
      scheduled.set(false);
    }
  }

  private record Retirement(Gloss plugin, AtomicBoolean scheduled) {
  }

  private static VisibilityGovernor governor() {
    VisibilityGovernor current = Gloss.instance == null ? null : Gloss.instance.governor();
    return current == null ? VisibilityGovernor.passthrough() : current;
  }

  private static void attachGroup(DisplayEntityGroup group, UUID handle) {
    entityGroups.put(handle, group);
    handlesByGroup.computeIfAbsent(group, ignored -> ConcurrentHashMap.newKeySet()).add(handle);
    groupsByViewer.computeIfAbsent(group.viewer().getUniqueId(), ignored -> ConcurrentHashMap.newKeySet()).add(group);
  }

  private static void detachGroup(UUID handle) {
    DisplayEntityGroup group = entityGroups.remove(handle);
    if (group == null) {
      return;
    }
    handlesByGroup.computeIfPresent(group, (ignored, handles) -> {
      handles.remove(handle);
      if (!handles.isEmpty()) {
        return handles;
      }
      groupsByViewer.computeIfPresent(group.viewer().getUniqueId(), (viewerId, groups) -> {
        groups.remove(group);
        return groups.isEmpty() ? null : groups;
      });
      return null;
    });
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

    DisplayEntityGroup group = entityGroups.get(uuid);
    if (group == null) {
      group = fallbackGroups.computeIfAbsent(player.getUniqueId(),
          ignored -> group(player, VisibilityGovernor.Surface.HOLOGRAM));
      attachGroup(group, uuid);
    }
    group.show(uuid);
  }

  public static void despawn(UUID uuid) {
    DisplayEntityGroup group = entityGroups.get(uuid);
    if (group != null) {
      group.hide(uuid, false);
      return;
    }
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
    DisplayEntityGroup group = entityGroups.get(uuid);
    if (group != null) {
      group.hide(uuid, true);
      return;
    }
    if (!displayEntities.containsKey(uuid))
      return;

    despawn(uuid);
    forgetEntity(displayEntities.remove(uuid));
  }

  public static void delete(UUID uuid, Player fallbackPlayer) {
    DisplayEntityGroup group = entityGroups.get(uuid);
    if (group != null) {
      group.hide(uuid, true);
      return;
    }
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

    Map<DisplayEntityGroup, List<UUID>> grouped = new IdentityHashMap<>();
    List<UUID> ungrouped = new ArrayList<>();
    for (UUID handle : uuids) {
      DisplayEntityGroup group = entityGroups.get(handle);
      if (group == null) {
        ungrouped.add(handle);
      } else {
        grouped.computeIfAbsent(group, ignored -> new ArrayList<>()).add(handle);
      }
    }
    for (Map.Entry<DisplayEntityGroup, List<UUID>> entry : grouped.entrySet()) {
      DisplayEntityGroup group = entry.getKey();
      group.batch(() -> {
        for (UUID handle : entry.getValue()) {
          group.hide(handle, true);
        }
      });
    }
    int capacity = ungrouped.size();
    boolean unsupported = unsupportedVersion();
    Map<Player, List<DisplayEntity>> byViewer = unsupported ? null : new IdentityHashMap<>(2);
    for (UUID uuid : ungrouped) {
      DisplayEntity displayEntity = displayEntities.remove(uuid);
      forgetEntity(displayEntity);
      Player player = unbind(uuid);
      if (unsupported || displayEntity == null)
        continue;
      Player target = player == null ? fallbackPlayer : player;
      if (target == null)
        continue;
      byViewer.computeIfAbsent(target, ignored -> new ArrayList<>(capacity))
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
    for (DisplayEntityGroup group : retirements.keySet()) {
      if (group.viewer().getUniqueId().equals(player.getUniqueId())) {
        group.disconnected();
        retirements.remove(group);
      }
    }
    fallbackGroups.remove(player.getUniqueId());
    Set<DisplayEntityGroup> groups = groupsByViewer.remove(player.getUniqueId());
    if (groups != null) {
      for (DisplayEntityGroup group : groups) {
        group.disconnected();
        Set<UUID> grouped = handlesByGroup.remove(group);
        if (grouped != null) {
          for (UUID handle : grouped) {
            entityGroups.remove(handle);
            forgetEntity(displayEntities.remove(handle));
          }
        }
      }
    }
    Set<UUID> handles = handlesByViewer.remove(player.getUniqueId());
    if (handles == null)
      return;
    for (UUID handle : handles) {
      playerVisibility.remove(handle);
    }
  }

  private record PacketTransport(Player viewer) implements DisplayEntityGroup.Transport {
    @Override
    public boolean spawn(UUID handle) {
      DisplayEntity entity = displayEntities.get(handle);
      if (entity == null || unsupportedVersion()) {
        return false;
      }
      if (!PacketUtils.sendChecked(viewer, entity.spawn())) {
        return false;
      }
      bind(handle, viewer);
      GlossTelemetry.countSpawnChurn();
      return true;
    }

    @Override
    public void remove(List<UUID> handles, boolean delete) {
      List<DisplayEntity> entities = new ArrayList<>(handles.size());
      for (UUID handle : handles) {
        DisplayEntity entity = displayEntities.get(handle);
        if (entity != null) {
          entities.add(entity);
        }
      }
      if (!entities.isEmpty() && !unsupportedVersion()) {
        int[] ids = new int[entities.size()];
        for (int index = 0; index < ids.length; index++) {
          ids[index] = entities.get(index).id();
        }
        List<PacketWrapper<?>> packets = new ArrayList<>(entities.size() + 1);
        packets.add(DisplayEntity.destroyAll(ids));
        for (DisplayEntity entity : entities) {
          if (entity.isRawEntity()) {
            packets.add(entity.collisionTeamRemove());
          }
        }
        if (!PacketUtils.sendChecked(viewer, packets) && viewer.isOnline()) {
          throw new IllegalStateException("Display channel is unavailable for " + viewer.getUniqueId());
        }
      }
      for (UUID handle : handles) {
        unbind(handle);
        if (delete) {
          forgetEntity(displayEntities.remove(handle));
          detachGroup(handle);
        }
      }
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
    if (displayEntity == null)
      return null;
    if (isAlreadyAt(displayEntity, location))
      return null;
    PacketWrapper<?> packet = displayEntity.goTo(location);
    return player == null ? null : packet;
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
    if (displayEntity == null)
      return;
    PacketWrapper<?> packet = displayEntity.move(offset);
    if (player != null) {
      PacketUtils.sendOne(player, packet);
    }
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
    if (displayEntity == null)
      return;
    if (!displayEntity.isTextDisplay())
      return;
    Component text = name == null ? Component.empty() : name;
    displayEntity.text(text);
    if (player != null) {
      PacketUtils.sendOne(player, DisplayEntity.textUpdate(displayEntity.id(), text));
    }
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
      if (displayEntity == null || !displayEntity.isTextDisplay()) {
        continue;
      }
      Component text = names.get(index) == null ? Component.empty() : names.get(index);
      if (text.equals(displayEntity.text())) {
        continue;
      }
      displayEntity.text(text);
      if (player != null) {
        byViewer.computeIfAbsent(player, ignored -> new ArrayList<>(uuids.size()))
            .add(DisplayEntity.textUpdate(displayEntity.id(), text));
      }
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
    if (displayEntity == null)
      return null;
    if (!displayEntity.isTextDisplay())
      return null;
    displayEntity.backgroundColor(backgroundColor);
    return player == null ? null : displayEntity.metadataPacket(MetadataIndex.TEXT_BACKGROUND);
  }

  public static void rescale(UUID uuid, Location anchor, float ratio) {
    DisplayEntity display = displayEntities.get(uuid);
    if (display == null || unsupportedVersion()) {
      return;
    }
    Player player = playerVisibility.get(uuid);
    Vector3d previous = display.location();
    Location position = anchor.clone().add(
        (previous.getX() - anchor.getX()) * ratio,
        (previous.getY() - anchor.getY()) * ratio,
        (previous.getZ() - anchor.getZ()) * ratio);
    List<PacketWrapper<?>> packets = new ArrayList<>(2);
    packets.add(display.goTo(position));
    if (!display.isRawEntity()) {
      Vector3f scale = display.scale();
      Vector3f translation = display.translation();
      display.scale(new Vector3f(scale.getX() * ratio, scale.getY() * ratio, scale.getZ() * ratio));
      display.translation(new Vector3f(translation.getX() * ratio, translation.getY() * ratio,
          translation.getZ() * ratio));
      packets.add(display.metadataPacket(MetadataIndex.TRANSLATION, MetadataIndex.SCALE));
    }
    if (player != null) {
      PacketUtils.send(player, packets);
    }
  }

  public static void changeScale(UUID uuid, float x, float y, float z) {
    if (unsupportedVersion())
      return;
    DisplayEntity displayEntity = displayEntities.get(uuid);
    Player player = playerVisibility.get(uuid);
    if (displayEntity == null)
      return;
    displayEntity.scale(new Vector3f(x, y, z));
    if (player != null) {
      PacketUtils.sendOne(player, displayEntity.metadataPacket(MetadataIndex.SCALE));
    }
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
    if (displayEntity == null)
      return null;
    displayEntity.scale(new Vector3f(x, y, z));
    displayEntity.translation(translation == null ? new Vector3f(0, 0, 0) : translation);
    return player == null ? null : displayEntity.metadataPacket(MetadataIndex.TRANSLATION, MetadataIndex.SCALE);
  }

  public static void changeItem(UUID uuid, ItemStack itemStack) {
    if (unsupportedVersion())
      return;
    DisplayEntity displayEntity = displayEntities.get(uuid);
    Player player = playerVisibility.get(uuid);
    if (displayEntity == null)
      return;
    if (!displayEntity.isItemDisplay())
      return;
    displayEntity.item(itemStack == null ? new ItemStack(Material.AIR) : itemStack.clone());
    if (player != null) {
      PacketUtils.sendOne(player, displayEntity.metadataPacket(MetadataIndex.CONTENT));
    }
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
