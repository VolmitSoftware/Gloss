package art.arcane.gloss.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HoloClick;
import art.arcane.gloss.api.HoloClickHandler;
import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.api.HoloCloseReason;
import art.arcane.gloss.api.internal.ApiClickGuard;
import art.arcane.gloss.api.internal.ApiEvents;
import art.arcane.gloss.api.internal.ApiMenuHandle;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.panel.PanelClickTarget;
import art.arcane.gloss.panel.PanelRuntimeManager;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.enums.NavigationMode;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.menu.action.MenuNavigationHistory;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import art.arcane.gloss.menu.components.ClickableComponent;
import art.arcane.gloss.preview.ContainerPreview;
import art.arcane.gloss.preview.ContainerPreviewAccess;
import art.arcane.gloss.preview.doc.CompiledPreviewDocument;
import art.arcane.gloss.preview.doc.PreviewDocumentRegistry;
import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.gloss.util.common.ParticleUtils;
import art.arcane.volmlib.util.bukkit.Events;
import art.arcane.volmlib.util.bukkit.papi.PlayerSnapshotStore;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class MenuSessionManager {

  /**
   * Where the Folia obstruction walk starts along the eye ray, matching the first sample the
   * previous fixed-step loop took.
   */
  private static final double OBSTRUCTION_START = 0.1D;
  static final int PREVIEW_DISCOVERY_LIMIT_PER_TICK = 10;
  static final int PREVIEW_FALLBACK_INTERVAL_TICKS = 100;

  private final Map<UUID, SessionHolder> holders = new ConcurrentHashMap<>();
  private final Map<UUID, PreviewMiss> previewMisses = new ConcurrentHashMap<>();
  private final PreviewDiscoveryQueue previewDiscoveryQueue = new PreviewDiscoveryQueue();
  private final PreviewFallbackSweep previewFallbackSweep = new PreviewFallbackSweep();

  private final PlayerSnapshotStore<String> openMenus = new PlayerSnapshotStore<>();

  private final ApiClickGuard clickGuard = new ApiClickGuard(Gloss.instance.getLogger(), System::currentTimeMillis,
      ApiClickGuard.DEFAULT_FAULT_LIMIT, ApiClickGuard.DEFAULT_SLOW_MILLIS);

  private SchedulerUtils.TaskHandle debugHitbox, debugPos;
  private final SchedulerUtils.TaskHandle holderTask, previewTask;
  private final PacketListenerCommon entityInteractionListener;
  private volatile boolean acceptingPreviewDiscovery = true;
  private int previewFallbackPhase;

  public PlayerSnapshotStore<String> getOpenMenus() {
    return openMenus;
  }

  public ApiClickGuard getClickGuard() {
    return clickGuard;
  }

  public MenuSessionManager() {
    applyDebugSettings();
    holderTask = SchedulerUtils.scheduleSyncTask(Gloss.instance, 1L, () -> {
      if (holders.isEmpty()) {
        return;
      }
      long tickStart = System.nanoTime();
      holders.values().forEach(holder -> {
        Player player = holder.player();
        Runnable tickTask = () -> {
          boolean previewActive = holder.hasPreview();
          boolean disposable = holder.tick();
          if (previewActive && player.isOnline()) {
            managePreviewEvents(player, false);
          }
          if (disposable) {
            disposeIfIdle(holder);
          }
        };

        SchedulerUtils.runEntity(Gloss.instance, player, tickTask);
      });
      GlossTelemetry.addTickNanos(System.nanoTime() - tickStart);
    }, false);
    Events.listen(Gloss.instance, PlayerMoveEvent.class, EventPriority.HIGHEST, e -> {
      if (holders.isEmpty() || e.isCancelled() || e.getTo() == null) return;
      SessionHolder holder = holders.get(e.getPlayer().getUniqueId());
      if (holder == null) return;
      holder.inspectSession(s -> s == null ? null : handleMovement(s, e.getFrom(), e.getTo()));
    });
    Events.listen(Gloss.instance, PlayerMoveEvent.class, EventPriority.MONITOR, e -> {
      if (e instanceof PlayerTeleportEvent || e.isCancelled() || e.getTo() == null) return;
      SessionHolder holder = holders.get(e.getPlayer().getUniqueId());
      if (holder == null || !holder.hasPreview()) {
        queuePreviewDiscovery(e.getPlayer(), false);
      }
    });
    Events.listen(Gloss.instance, PlayerDeathEvent.class, EventPriority.MONITOR, e -> {
      SessionHolder holder = holders.get(e.getEntity().getUniqueId());
      if (holder == null) return;
      holder.inspectSession(s -> s != null && s.isCloseOnDeath() ? HoloCloseReason.DEATH : null);
    });
    Events.listen(Gloss.instance, PlayerRespawnEvent.class, EventPriority.MONITOR, e -> {
      SessionHolder holder = holders.get(e.getPlayer().getUniqueId());
      if (holder != null) {
        holder.inspectSession(s -> {
          if (s == null) return null;
          if (!s.isValid(e.getRespawnLocation())) return HoloCloseReason.RESPAWN;
          if (s.isFollowPlayer()) {
            s.follow(e.getRespawnLocation());
          } else {
            s.move(e.getRespawnLocation());
          }
          return null;
        });
      }
      if (holder == null || !holder.hasPreview()) {
        queuePreviewDiscovery(e.getPlayer(), false);
      }
    });
    Events.listen(Gloss.instance, PlayerTeleportEvent.class, EventPriority.MONITOR, e -> {
      SessionHolder holder = holders.get(e.getPlayer().getUniqueId());
      if (holder != null && e.getTo() != null) {
        holder.inspectSession(s -> {
          if (s == null) return null;
          if (!s.isValid(e.getTo()) || s.isCloseOnTeleport()) return HoloCloseReason.TELEPORT;
          if (s.isFollowPlayer()) {
            s.follow(e.getTo());
          } else {
            s.move(e.getTo());
          }
          return null;
        });
      }
      if (!e.isCancelled() && e.getTo() != null && (holder == null || !holder.hasPreview())) {
        queuePreviewDiscovery(e.getPlayer(), false);
      }
    });
    Events.listen(Gloss.instance, PlayerJoinEvent.class, EventPriority.MONITOR, e -> {
      queuePreviewDiscovery(e.getPlayer(), false);
    });
    Events.listen(Gloss.instance, PlayerQuitEvent.class, EventPriority.MONITOR, e -> {
      DisplayEntityManager.forget(e.getPlayer());
      previewMisses.remove(e.getPlayer().getUniqueId());
      previewDiscoveryQueue.remove(e.getPlayer().getUniqueId());
      SessionHolder holder = holders.remove(e.getPlayer().getUniqueId());
      if (holder == null) return;
      holder.close(HoloCloseReason.QUIT);
    });
    Events.listen(Gloss.instance, PlayerInteractEvent.class, EventPriority.HIGHEST, this::dispatchClick);
    entityInteractionListener = PacketEvents.getAPI() == null
        || PacketEvents.getAPI().getEventManager() == null
        ? null
        : PacketEvents.getAPI().getEventManager().registerListener(
            new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
              @Override
              public void onPacketReceive(PacketReceiveEvent event) {
                dispatchRawEntityClick(event);
              }
            }
        );
    previewTask = listenToInventoryPreview();
  }

  /**
   * Drops a holder that reported itself finished, but only while it is still finished — the map
   * operation is atomic against the {@code computeIfAbsent} that opens a menu or preview, so a
   * session opened during the tick cannot be orphaned by the sweep.
   */
  private void disposeIfIdle(SessionHolder holder) {
    holders.computeIfPresent(holder.playerId(),
        (id, current) -> current == holder && current.isDisposable() ? null : current);
  }

  private void dispatchClick(PlayerInteractEvent event) {
    if (event.isCancelled()) return;
    Action action = event.getAction();
    if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK
        && action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
    if (event.getHand() == EquipmentSlot.OFF_HAND) return;

    HoloClickTrigger trigger = HoloClickTrigger.fromInteraction(action, event.getPlayer().isSneaking());
    if (dispatchClick(event.getPlayer(), trigger)) {
      event.setCancelled(true);
    }
  }

  private void dispatchRawEntityClick(PacketReceiveEvent event) {
    if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
      return;
    }
    Player player = event.getPlayer();
    if (player == null) {
      return;
    }
    WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
    if (packet.getHand() == InteractionHand.OFF_HAND
        || !DisplayEntityManager.isVisibleRawEntity(player, packet.getEntityId())) {
      return;
    }

    event.setCancelled(true);
    boolean sneaking = packet.isSneaking().orElse(player.isSneaking());
    HoloClickTrigger trigger = rawEntityTrigger(packet.getAction(), sneaking);
    SchedulerUtils.runEntity(Gloss.instance, player, () -> {
      if (player.isOnline()) {
        dispatchClick(player, trigger);
      }
    });
  }

  static HoloClickTrigger rawEntityTrigger(WrapperPlayClientInteractEntity.InteractAction action,
                                           boolean sneaking) {
    if (action == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
      return sneaking ? HoloClickTrigger.SHIFT_LEFT_CLICK : HoloClickTrigger.LEFT_CLICK;
    }
    return sneaking ? HoloClickTrigger.SHIFT_RIGHT_CLICK : HoloClickTrigger.RIGHT_CLICK;
  }

  private boolean dispatchClick(Player player, HoloClickTrigger trigger) {
    SessionHolder holder = holders.isEmpty() ? null : holders.get(player.getUniqueId());
    SessionHolder.ClickSnapshot snapshot = holder == null
        ? null
        : holder.snapshotClick(player.getEyeLocation());
    PanelRuntimeManager boardRuntime = Gloss.instance.getPanelRuntime();
    PanelClickTarget boardTarget = boardRuntime == null
        ? null
        : boardRuntime.findClickTarget(player);
    if (snapshot == null && boardTarget == null) return false;

    double nearestDistance = snapshot == null
        ? boardTarget.distance()
        : boardTarget == null ? snapshot.distance() : Math.min(snapshot.distance(), boardTarget.distance());
    if (isInteractionObstructed(player, nearestDistance)) return false;

    if (boardTarget != null && (snapshot == null || boardTarget.distance() < snapshot.distance())) {
      try {
        boardTarget.dispatch(trigger);
      } catch (Exception ex) {
        Gloss.logExceptionStackThrottled(false, "panel-component-click", ex,
            "Board component %s of board %s threw while handling a click from %s.",
            boardTarget.component().getId(), boardTarget.view().definition().id(), player.getName());
      }
      return true;
    }

    dispatchPersonalClick(player, snapshot, trigger);
    return true;
  }

  private boolean isInteractionObstructed(Player player, double distance) {
    Location eye = player.getEyeLocation();
    World world = eye.getWorld();
    if (world == null) {
      return true;
    }
    if (FoliaScheduler.isFolia(Gloss.instance)) {
      return isFoliaInteractionObstructed(world, eye, distance);
    }
    RayTraceResult obstruction = world.rayTraceBlocks(
        eye,
        eye.getDirection(),
        distance,
        FluidCollisionMode.NEVER,
        true
    );
    if (obstruction == null) {
      return false;
    }
    double obstructionDistanceSquared = obstruction.getHitPosition().distanceSquared(eye.toVector());
    return obstructionDistanceSquared + 1.0E-6D < distance * distance;
  }

  private boolean isFoliaInteractionObstructed(World world, Location eye, double distance) {
    return isVoxelObstructed(world, eye, distance, FoliaScheduler::isOwnedByCurrentRegion);
  }

  /**
   * Folia's stand-in for {@code rayTraceBlocks}, which may only touch blocks the calling region
   * owns. Walks the exact voxels the eye ray crosses (DDA) instead of sampling every 0.1 blocks,
   * resolves region ownership once per chunk column rather than once per sample, and — the fix —
   * treats a voxel the current region does not own as <b>passable</b>.
   *
   * <p>Treating foreign voxels as obstructing is what made a menu straddling a region boundary
   * silently unclickable: the ray left the clicker's region before reaching the button, so every
   * click was swallowed. Occlusion by blocks in a foreign region is therefore best-effort on Folia;
   * the Paper path still does a real ray trace and is unchanged.
   */
  static boolean isVoxelObstructed(World world, Location eye, double distance, RegionOwnership ownership) {
    if (distance <= OBSTRUCTION_START) {
      return false;
    }
    Vector direction = eye.getDirection();
    double length = direction.length();
    if (length < 1.0E-9D) {
      return false;
    }
    double dirX = direction.getX() / length;
    double dirY = direction.getY() / length;
    double dirZ = direction.getZ() / length;
    double startX = eye.getX() + dirX * OBSTRUCTION_START;
    double startY = eye.getY() + dirY * OBSTRUCTION_START;
    double startZ = eye.getZ() + dirZ * OBSTRUCTION_START;
    double reach = distance - OBSTRUCTION_START;

    int x = (int) Math.floor(startX);
    int y = (int) Math.floor(startY);
    int z = (int) Math.floor(startZ);
    int stepX = dirX > 0.0D ? 1 : dirX < 0.0D ? -1 : 0;
    int stepY = dirY > 0.0D ? 1 : dirY < 0.0D ? -1 : 0;
    int stepZ = dirZ > 0.0D ? 1 : dirZ < 0.0D ? -1 : 0;
    double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dirX);
    double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dirY);
    double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dirZ);
    double nextX = boundary(startX, x, stepX, dirX);
    double nextY = boundary(startY, y, stepY, dirY);
    double nextZ = boundary(startZ, z, stepZ, dirZ);

    int minHeight = world.getMinHeight();
    int maxHeight = world.getMaxHeight();
    int columnX = Integer.MIN_VALUE;
    int columnZ = Integer.MIN_VALUE;
    boolean columnOwned = false;

    while (true) {
      if (y >= minHeight && y < maxHeight) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        if (chunkX != columnX || chunkZ != columnZ) {
          columnX = chunkX;
          columnZ = chunkZ;
          columnOwned = ownership.owns(world, chunkX, chunkZ);
        }
        if (columnOwned && !world.getBlockAt(x, y, z).isPassable()) {
          return true;
        }
      }

      double traveled;
      if (nextX <= nextY && nextX <= nextZ) {
        traveled = nextX;
        x += stepX;
        nextX += deltaX;
      } else if (nextY <= nextZ) {
        traveled = nextY;
        y += stepY;
        nextY += deltaY;
      } else {
        traveled = nextZ;
        z += stepZ;
        nextZ += deltaZ;
      }
      if (traveled + 1.0E-6D >= reach) {
        return false;
      }
    }
  }

  private static double boundary(double start, int voxel, int step, double direction) {
    if (step == 0) {
      return Double.POSITIVE_INFINITY;
    }
    return (step > 0 ? voxel + 1 - start : start - voxel) / Math.abs(direction);
  }

  /** Whether the calling thread's region owns a chunk column; a seam so the walk is testable. */
  interface RegionOwnership {
    boolean owns(World world, int chunkX, int chunkZ);
  }

  private void dispatchPersonalClick(Player player, SessionHolder.ClickSnapshot snapshot,
                                     HoloClickTrigger trigger) {
    ApiMenuHandle handle = snapshot.handle();
    String ownerName = handle == null ? null : handle.owner().name();

    ClickableComponent<?> component = snapshot.component();
    if (!ApiEvents.fireClick(player, snapshot.menuId(), component.getId(), ownerName, trigger)) {
      return;
    }

    try {
      component.onClick(trigger);
    } catch (Exception ex) {
      Gloss.logExceptionStackThrottled(false, "menu-component-click", ex,
          "Menu component %s of menu %s threw while handling a click from %s.",
          component.getId(), snapshot.menuId(), player.getName());
    }

    if (handle == null || !handle.live()) {
      return;
    }

    HoloClickHandler apiHandler = handle.handler(component.getId());
    if (apiHandler != null) {
      clickGuard.dispatch(handle.owner(), apiHandler,
          new HoloClick(player, snapshot.menuId(), component.getId(), trigger, handle));
    }
  }

  public int holderCount() {
    return holders.size();
  }

  public boolean openLastSession(Player p) {
    return navigateSession(p, new NavigationRequest(NavigationMode.BACK, null))
        == NavigationResult.APPLIED;
  }

  public NavigationResult navigateSession(Player player, NavigationRequest request) {
    SessionHolder holder = holders.get(player.getUniqueId());
    if (request.mode() == NavigationMode.CLOSE) {
      return holder != null && holder.closeSession(false, HoloCloseReason.CLOSED_BY_COMMAND)
          ? NavigationResult.APPLIED
          : NavigationResult.NO_HISTORY;
    }
    if (!GlossConfig.current().menus().enabled()) {
      return NavigationResult.DENIED;
    }

    String target = MenuNavigationHistory.resolveTarget(
        request.mode(),
        request.target(),
        holder == null ? null : holder.lastSessionId(),
        holder == null ? null : holder.rootSessionId()
    );
    if (target == null) {
      return NavigationResult.NO_HISTORY;
    }

    MenuDefinitionData menu = Gloss.instance.getMenuCatalog().definition(target).orElse(null);
    if (menu == null) {
      Gloss.instance.getLocalization().send(player,
          GlossMessages.MENU_UNAVAILABLE,
          MessageArgs.builder().untrusted("menu", target).build()
      );
      return NavigationResult.NOT_FOUND;
    }
    if (!player.hasPermission("gloss.open." + menu.getId())) {
      Gloss.instance.getLocalization().send(player,
          GlossMessages.MENU_PERMISSION_DENIED,
          MessageArgs.builder().untrusted("menu", menu.getId()).build()
      );
      return NavigationResult.DENIED;
    }
    if (!ApiEvents.fireOpen(player, menu.getId(), null)) {
      return NavigationResult.DENIED;
    }

    SessionHolder activeHolder = holder == null ? holder(player) : holder;
    return activeHolder.navigateSession(menu, request);
  }

  public void createNewSession(Player p, MenuDefinitionData menu) {
    createNewSession(p, menu, null);
  }

  public boolean createNewSession(Player p, MenuDefinitionData menu, ApiMenuHandle handle) {
    if (!GlossConfig.current().menus().enabled()) {
      return false;
    }
    if (!ApiEvents.fireOpen(p, menu.getId(), handle == null ? null : handle.owner().name())) {
      return false;
    }

    holder(p).openSession(menu, handle);
    return true;
  }

  public boolean hasMenuSession(Player p) {
    SessionHolder holder = holders.get(p.getUniqueId());
    return holder != null && holder.hasSession();
  }

  public boolean moveSession(Player p) {
    SessionHolder holder = holders.get(p.getUniqueId());
    return holder != null && holder.moveSession(p.getLocation());
  }

  public boolean destroySessionFor(Player p, ApiMenuHandle handle, HoloCloseReason reason) {
    SessionHolder holder = holders.get(p.getUniqueId());
    return holder != null && holder.closeSessionOf(handle, reason);
  }

  public void addPreviewSession(Player p, ContainerPreview session) {
    holder(p).openPreview(session);
  }

  public boolean hasPreviewSession(Player p) {
    SessionHolder holder = holders.get(p.getUniqueId());
    return holder != null && holder.hasPreview();
  }

  public boolean destroySession(Player p, boolean history) {
    return destroySession(p, history, HoloCloseReason.CLOSED_BY_COMMAND);
  }

  public boolean destroySession(Player p, boolean history, HoloCloseReason reason) {
    SessionHolder holder = holders.get(p.getUniqueId());
    if (holder == null) return false;
    return holder.closeSession(history, reason);
  }

  public void destroyAll() {
    acceptingPreviewDiscovery = false;
    if (PacketEvents.getAPI() != null
        && PacketEvents.getAPI().getEventManager() != null
        && entityInteractionListener != null) {
      PacketEvents.getAPI().getEventManager().unregisterListener(entityInteractionListener);
    }
    cancel(holderTask);
    cancel(previewTask);
    cancel(debugHitbox);
    cancel(debugPos);
    holders.values().forEach(holder -> holder.close(HoloCloseReason.GLOSS_SHUTDOWN));
    holders.clear();
    previewMisses.clear();
    previewDiscoveryQueue.clear();
    previewFallbackSweep.clear();
    openMenus.clear();
  }

  private static void cancel(SchedulerUtils.TaskHandle handle) {
    if (handle != null && !handle.isCancelled()) {
      handle.cancel();
    }
  }

  private SessionHolder holder(Player player) {
    return holders.computeIfAbsent(player.getUniqueId(), id -> new SessionHolder(player, openMenus));
  }

  public void destroyAllType(String id, Consumer<Player> consumer) {
    holders.values().forEach(holder -> {
      Player player = holder.player();
      Runnable destroyTask = () -> {
        boolean closed = holder.inspectSession(session ->
            session != null && session.getId().equals(id) ? HoloCloseReason.DEFINITION_RELOADED : null);
        if (closed) {
          consumer.accept(player);
        }
      };

      SchedulerUtils.runEntity(Gloss.instance, player, destroyTask);
    });
  }

  public void refreshVisuals() {
    holders.values().forEach(holder -> {
      Runnable refreshTask = holder::refreshVisuals;
      SchedulerUtils.runEntity(Gloss.instance, holder.player(), refreshTask);
    });
  }

  public void closeAllPreviews() {
    holders.values().forEach(holder -> {
      Player player = holder.player();
      Runnable closeTask = () -> {
        boolean previewActive = holder.hasPreview();
        holder.closePreview();
        if (previewActive && player.isOnline()) {
          queuePreviewDiscovery(player, true);
        }
      };
      SchedulerUtils.runEntity(Gloss.instance, player, closeTask);
    });
  }

  public void applyDebugSettings() {
    GlossConfig.Debug debug = GlossConfig.current().debug();
    controlHitboxDebug(debug.hitbox());
    controlPositionDebug(debug.position());
  }

  static HoloCloseReason handleMovement(MenuSession session, Location from, Location to) {
    if (session.isFreezePlayer()) {
      to.setX(from.getX());
      to.setY(from.getY());
      to.setZ(from.getZ());
      Player player = session.getPlayer();
      Vector velocity = player.getVelocity();
      if (velocity.getX() != 0 || velocity.getY() != 0 || velocity.getZ() != 0) {
        player.setVelocity(new Vector());
      }
      if (session.isFollowPlayer()) {
        session.follow(to);
      }
      return null;
    }

    if (!session.isValid(to)) {
      return HoloCloseReason.MOVED_OUT_OF_RANGE;
    }

    if (session.isFollowPlayer()) {
      session.follow(to);
    }

    return null;
  }

  public void controlHitboxDebug(boolean hitbox) {
    if (hitbox && (debugHitbox == null || debugHitbox.isCancelled())) {
      debugHitbox = SchedulerUtils.scheduleSyncTask(Gloss.instance, 2L, () -> holders.values().forEach(holder -> {
        Player player = holder.player();
        Runnable debugTask = () -> holder.onSession(session -> {
          if (session == null) return;
          session.getComponents().forEach(c -> {
            if (c instanceof ClickableComponent<?> o)
              o.highlightHitbox(player.getWorld());
          });
        });

        SchedulerUtils.runEntity(Gloss.instance, player, debugTask);
      }), false);
    } else if (!hitbox && (debugHitbox != null && !debugHitbox.isCancelled()))
      debugHitbox.cancel();
  }

  //TODO Fix anchor particle
  public void controlPositionDebug(boolean positionDebug) {
    if (positionDebug && (debugPos == null || debugPos.isCancelled())) {
      debugPos = SchedulerUtils.scheduleSyncTask(Gloss.instance, 2L, () -> holders.values().forEach(holder -> {
        Player player = holder.player();
        Runnable debugTask = () -> {
          World world = player.getWorld();
          holder.onSession(s -> {
            if (s == null) return;
            ParticleUtils.playParticle(world, s.getCenterPoint().toVector(), Color.YELLOW);
            s.getComponents().forEach(c -> ParticleUtils.playParticle(world, c.getLocation().toVector(), Color.ORANGE));
          });
        };

        SchedulerUtils.runEntity(Gloss.instance, player, debugTask);
      }), false);
    } else if (!positionDebug && (debugPos != null && !debugPos.isCancelled()))
      debugPos.cancel();
  }

  private SchedulerUtils.TaskHandle listenToInventoryPreview() {
    return SchedulerUtils.scheduleSyncTask(Gloss.instance, 1L, () -> {
      if (!ContainerPreviewAccess.isEnabled()) {
        previewMisses.clear();
        previewDiscoveryQueue.clear();
        previewFallbackSweep.clear();
        previewFallbackPhase = 0;
        return;
      }
      if (previewFallbackPhase == 0) {
        previewFallbackSweep.begin(Bukkit.getOnlinePlayers());
      }
      previewFallbackSweep.drainBatch(player -> queuePreviewDiscovery(player, true));
      previewDiscoveryQueue.drain(PREVIEW_DISCOVERY_LIMIT_PER_TICK, this::schedulePreviewDiscovery);
      previewFallbackPhase++;
      if (previewFallbackPhase >= PREVIEW_FALLBACK_INTERVAL_TICKS) {
        previewFallbackPhase = 0;
      }
    }, false);
  }

  void managePreviewEvents(Player p, boolean forceRescan) {
    try {
      UUID playerId = p.getUniqueId();
      SessionHolder holder = holders.get(playerId);
      if (!ContainerPreviewAccess.isEnabled() || !p.isOnline()) {
        previewMisses.remove(playerId);
        if (holder != null) {
          holder.closePreview();
        }
        return;
      }

      Location eye = p.getEyeLocation();
      PreviewMiss miss = previewMisses.get(playerId);
      if (!forceRescan && miss != null && miss.matches(eye)) {
        return;
      }
      if (!forceRescan && holder != null
          && holder.stableAim(eye) instanceof PreviewTarget held && stillEligible(held)) {
        return;
      }

      PreviewTarget target = getLookedAtPreviewTarget(p, eye);
      if (target == null) {
        previewMisses.put(playerId, PreviewMiss.at(eye));
        if (holder != null) {
          holder.recordAim(eye, null);
          holder.closePreview();
        }
        return;
      }

      previewMisses.remove(playerId);
      if (holder == null) {
        holder = holder(p);
      }
      holder.recordAim(eye, target);

      SessionHolder finalHolder = holder;
      holder.onPreview(preview -> {
        if (preview == null) {
          createNewPreviewSession(target, p, eye);
          return;
        }

        if (!target.matches(preview)) {
          finalHolder.closePreview();
          createNewPreviewSession(target, p, eye);
        }
      });
    } catch (Exception ex) {
      Gloss.logExceptionStackThrottled(false, "container-preview-update", ex,
          "Failed to manage inventory preview for %s.", p.getName());
    }
  }

  private void queuePreviewDiscovery(Player player, boolean forceRescan) {
    if (!acceptingPreviewDiscovery || !ContainerPreviewAccess.isEnabled()) {
      return;
    }
    previewDiscoveryQueue.offer(player, forceRescan);
  }

  private void schedulePreviewDiscovery(PreviewDiscovery discovery) {
    Player player = discovery.player();
    UUID playerId = discovery.playerId();
    Runnable scan = () -> {
      try {
        if (previewDiscoveryQueue.isPending(discovery)) {
          managePreviewEvents(player, discovery.forceRescan());
        }
      } finally {
        previewDiscoveryQueue.complete(discovery);
      }
    };
    Runnable retired = () -> {
      previewDiscoveryQueue.discard(discovery);
      previewMisses.remove(playerId);
    };
    try {
      if (!FoliaScheduler.runEntity(Gloss.instance, player, scan, 1L, retired)) {
        retired.run();
      }
    } catch (RuntimeException failure) {
      retired.run();
      Gloss.logExceptionStackThrottled(false, "container-preview-scheduling", failure,
          "Failed to schedule preview discovery for %s.", player.getName());
    }
  }

  /**
   * Whether a target the previous scan acquired is still one a scan could acquire. Together with an
   * unmoved eye this is what lets a tick skip the two ray traces: the ray is identical and its
   * winner still qualifies. A container that is broken, or a cart that dies, fails here and the
   * scan runs, so the preview still closes on the tick the target goes away.
   */
  private boolean stillEligible(PreviewTarget target) {
    if (target.block() != null) {
      Material type = target.block().getType();
      return type != Material.AIR && isPreviewBlockType(type);
    }
    Entity entity = target.entity();
    return entity != null && entity.isValid() && isPreviewEntity(entity);
  }

  /**
   * The block a player's eye ray hits within the preview look distance, ignoring passable blocks,
   * or null when the ray hits nothing (or the player's world is unloaded). Mirrors only the block
   * half of {@link #getLookedAtPreviewTarget}, without its per-document {@code isPreviewBlockType}
   * gate, so a caller can test one specific document's own matcher against whatever block the
   * player is actually looking at — which is what {@code /gloss preview dump} needs.
   */
  public Block lookedAtBlock(Player player) {
    Location eyeLocation = player.getEyeLocation();
    World world = eyeLocation.getWorld();
    if (world == null) {
      return null;
    }
    RayTraceResult blockResult = world.rayTraceBlocks(
        eyeLocation,
        eyeLocation.getDirection(),
        GlossConfig.current().previews().lookDistance(),
        FluidCollisionMode.NEVER,
        true
    );
    Block targetBlock = blockResult == null ? null : blockResult.getHitBlock();
    return targetBlock != null && targetBlock.getType() != Material.AIR ? targetBlock : null;
  }

  /**
   * What the player is looking at, if anything a preview document claims.
   *
   * <p><b>This is deliberately permission-blind.</b> A viewer without {@code gloss.preview} still
   * acquires the target; the permission decides only whether the card built downstream is the
   * locked padlock one. Never add a permission prefilter here —
   * {@code CharacterizationPreviewRaycastTest} fails if anyone does.
   *
   * <p>Both traces are held to the smallest span that can still change the answer: the entity trace
   * is capped where a block hit would beat it anyway (the {@code +0.01} squared tie-break, in
   * linear form), and skipped outright when no document declares an entity matcher — the filter
   * would have rejected everything.
   */
  private PreviewTarget getLookedAtPreviewTarget(Player player) {
    return getLookedAtPreviewTarget(player, null);
  }

  private PreviewTarget getLookedAtPreviewTarget(Player player, Location knownEye) {
    if (!ContainerPreviewAccess.isEnabled()) {
      return null;
    }
    Location eyeLocation = knownEye == null ? player.getEyeLocation() : knownEye;
    World world = eyeLocation.getWorld();
    if (world == null) {
      return null;
    }
    double lookDistance = GlossConfig.current().previews().lookDistance();
    Vector direction = eyeLocation.getDirection();
    RayTraceResult blockResult = world.rayTraceBlocks(
        eyeLocation,
        direction,
        lookDistance,
        FluidCollisionMode.NEVER,
        true
    );
    Block targetBlock = blockResult == null ? null : blockResult.getHitBlock();
    boolean occluding = targetBlock != null && targetBlock.getType() != Material.AIR;
    PreviewTarget blockTarget = occluding && isPreviewBlockType(targetBlock.getType())
        ? PreviewTarget.block(targetBlock)
        : null;

    PreviewDocumentRegistry registry = previewRegistry();
    if (registry == null || !registry.hasEntityMatchers()) {
      return blockTarget;
    }

    double blockDistance = occluding ? hitDistanceSquared(eyeLocation, blockResult) : Double.MAX_VALUE;
    double entityReach = blockDistance == Double.MAX_VALUE
        ? lookDistance
        : Math.min(lookDistance, Math.sqrt(blockDistance + 0.01D));
    RayTraceResult entityResult = world.rayTraceEntities(
        eyeLocation,
        direction,
        entityReach,
        0.35D,
        this::isPreviewEntity
    );
    Entity targetEntity = entityResult == null ? null : entityResult.getHitEntity();
    if (targetEntity == null) {
      return blockTarget;
    }
    if (occluding && blockDistance + 0.01D < hitDistanceSquared(eyeLocation, entityResult)) {
      return blockTarget;
    }
    return PreviewTarget.entity(targetEntity);
  }

  private void createNewPreviewSession(PreviewTarget target, Player p, Location scanEye) {
    ContainerPreviewAccess.ViewerAccess access = ContainerPreviewAccess.capture(p);
    if (target.block() != null) {
      createNewBlockPreviewSession(target.block(), p, access, scanEye);
      return;
    }
    if (target.entity() != null) {
      createNewEntityPreviewSession(target.entity(), p, access, scanEye);
    }
  }

  private void createNewBlockPreviewSession(Block b, Player p, ContainerPreviewAccess.ViewerAccess access,
                                            Location scanEye) {
    Runnable createTask = () -> {
      if (b.getType() == Material.AIR) {
        return;
      }
      boolean canOpen = ContainerPreviewAccess.canOpen(p, b, access);
      if (!canOpen) {
        ContainerPreview lockedSession = ContainerPreview.locked(b, p);
        openPreviewIfCurrent(PreviewTarget.block(b), p, lockedSession, scanEye);
        return;
      }
      if (isEnderChestDocument(b)) {
        Vector center = b.getLocation().toVector().add(new Vector(0.5D, 0.5D, 0.5D));
        Runnable buildTask = () -> {
          ContainerPreview newSession = ContainerPreview.forEnderChest(b, p, center);
          openPreviewIfCurrent(PreviewTarget.block(b), p, newSession, scanEye);
        };
        if (!SchedulerUtils.runEntity(Gloss.instance, p, buildTask)) {
          SessionHolder holder = holders.get(p.getUniqueId());
          if (holder != null) {
            holder.closePreview();
          }
        }
        return;
      }
      Runnable buildTask = () -> {
        ContainerPreview newSession = ContainerPreview.forBlock(b, p);
        openPreviewIfCurrent(PreviewTarget.block(b), p, newSession, scanEye);
      };
      buildTask.run();
    };

    if (!FoliaScheduler.runRegion(Gloss.instance, b.getLocation(), createTask)
        && !FoliaScheduler.isFolia(Gloss.instance.getServer())) {
      createTask.run();
    }
  }

  private void createNewEntityPreviewSession(Entity entity, Player p, ContainerPreviewAccess.ViewerAccess access,
                                             Location scanEye) {
    Runnable createTask = () -> {
      if (!entity.isValid() || !isPreviewEntity(entity)) {
        return;
      }
      ContainerPreview newSession = ContainerPreviewAccess.canOpen(p, entity, access)
          ? ContainerPreview.forEntity(entity, p)
          : ContainerPreview.locked(entity, p);
      openPreviewIfCurrent(PreviewTarget.entity(entity), p, newSession, scanEye);
    };

    if (!SchedulerUtils.runEntity(Gloss.instance, entity, createTask)) {
      SessionHolder holder = holders.get(p.getUniqueId());
      if (holder != null) {
        holder.closePreview();
      }
    }
  }

  private void openPreviewIfCurrent(PreviewTarget target, Player p, ContainerPreview newSession, Location scanEye) {
    if (newSession == null) {
      return;
    }
    Runnable openTask = () -> {
      if (!newSession.canView() || !isStillLookingAt(p, target, scanEye)) {
        newSession.close();
        return;
      }
      addPreviewSession(p, newSession);
    };
    if (!SchedulerUtils.runEntity(Gloss.instance, p, openTask)) {
      openTask.run();
    }
  }

  /**
   * Re-checks that the player has not looked away between acquiring the target and building the
   * card. An eye pose identical to the scan's answers that without a second pair of ray traces; on
   * Folia the build may have hopped regions and genuinely deferred, so the traces are redone.
   */
  private boolean isStillLookingAt(Player player, PreviewTarget target, Location scanEye) {
    if (scanEye != null && !FoliaScheduler.isFolia(Gloss.instance)) {
      Location eye = player.getEyeLocation();
      if (eye.getX() == scanEye.getX() && eye.getY() == scanEye.getY() && eye.getZ() == scanEye.getZ()
          && eye.getYaw() == scanEye.getYaw() && eye.getPitch() == scanEye.getPitch()) {
        return true;
      }
    }
    return target.matches(getLookedAtPreviewTarget(player));
  }

  private double hitDistanceSquared(Location eyeLocation, RayTraceResult rayTraceResult) {
    if (rayTraceResult == null || rayTraceResult.getHitPosition() == null) {
      return Double.MAX_VALUE;
    }
    return eyeLocation.toVector().distanceSquared(rayTraceResult.getHitPosition());
  }

  private boolean isPreviewBlockType(Material type) {
    PreviewDocumentRegistry registry = previewRegistry();
    return registry != null && registry.isPreviewBlockType(type);
  }

  /**
   * Whether this block's winning document is the ender-chest one, i.e. whether the preview must be
   * built from the viewer's own ender chest rather than from a tile entity. Driven off the resolved
   * document instead of {@code Material.ENDER_CHEST} so that a higher-priority user document naming
   * the block still wins, and so that dropping {@code special} from {@code ender_chest.json} makes
   * the block take the ordinary block path rather than stranding it on a null preview.
   */
  private boolean isEnderChestDocument(Block block) {
    PreviewDocumentRegistry registry = previewRegistry();
    CompiledPreviewDocument.Resolved resolved = registry == null ? null : registry.forBlock(block.getType());
    return resolved != null && PreviewDocumentRegistry.SPECIAL_ENDER_CHEST.equals(resolved.doc().special());
  }

  private boolean isPreviewEntity(Entity entity) {
    PreviewDocumentRegistry registry = previewRegistry();
    return registry != null && registry.isPreviewEntity(entity);
  }

  private static PreviewDocumentRegistry previewRegistry() {
    Gloss plugin = Gloss.instance;
    return plugin == null ? null : plugin.getPreviewRegistry();
  }

  record PreviewTarget(Block block, Entity entity) {

    private static PreviewTarget block(Block block) {
      return new PreviewTarget(block, null);
    }

    private static PreviewTarget entity(Entity entity) {
      return new PreviewTarget(null, entity);
    }

    private boolean matches(ContainerPreview preview) {
      if (block != null) {
        return preview.matchesBlock(block);
      }
      return preview.matchesEntity(entity);
    }

    private boolean matches(PreviewTarget other) {
      if (other == null) {
        return false;
      }
      if (block != null || other.block != null) {
        return block != null && other.block != null && block.equals(other.block);
      }
      return entity != null
          && other.entity != null
          && entity.getUniqueId().equals(other.entity.getUniqueId());
    }
  }

  private record PreviewMiss(World world, double x, double y, double z, float yaw, float pitch) {

    private static PreviewMiss at(Location eye) {
      return new PreviewMiss(eye.getWorld(), eye.getX(), eye.getY(), eye.getZ(), eye.getYaw(), eye.getPitch());
    }

    private boolean matches(Location eye) {
      return eye.getWorld() == world && eye.getX() == x && eye.getY() == y && eye.getZ() == z
          && eye.getYaw() == yaw && eye.getPitch() == pitch;
    }
  }

  static final class PreviewDiscoveryQueue {
    private final ArrayDeque<PreviewDiscovery> queued = new ArrayDeque<>();
    private final Map<UUID, PreviewDiscovery> pending = new HashMap<>();

    synchronized boolean offer(Player player, boolean forceRescan) {
      UUID playerId = player.getUniqueId();
      PreviewDiscovery existing = pending.get(playerId);
      if (existing != null) {
        if (existing.inFlight) {
          existing.rerun = true;
          existing.rerunForce |= forceRescan;
        } else {
          existing.forceRescan |= forceRescan;
        }
        return false;
      }

      PreviewDiscovery discovery = new PreviewDiscovery(player, playerId, forceRescan);
      pending.put(playerId, discovery);
      queued.addLast(discovery);
      return true;
    }

    int drain(int limit, Consumer<PreviewDiscovery> action) {
      if (limit <= 0) {
        return 0;
      }
      int available = Math.min(limit, queuedSize());
      int drained = 0;
      while (drained < available) {
        PreviewDiscovery discovery = claim();
        if (discovery == null) {
          break;
        }
        try {
          action.accept(discovery);
        } catch (RuntimeException | Error failure) {
          discard(discovery);
          throw failure;
        }
        drained++;
      }
      return drained;
    }

    synchronized void complete(PreviewDiscovery discovery) {
      if (!discovery.inFlight || pending.get(discovery.playerId()) != discovery) {
        return;
      }
      if (discovery.rerun) {
        discovery.inFlight = false;
        discovery.forceRescan = discovery.rerunForce;
        discovery.rerun = false;
        discovery.rerunForce = false;
        queued.addLast(discovery);
        return;
      }
      pending.remove(discovery.playerId());
      discovery.inFlight = false;
    }

    synchronized void discard(PreviewDiscovery discovery) {
      if (pending.get(discovery.playerId()) != discovery) {
        return;
      }
      pending.remove(discovery.playerId());
      queued.remove(discovery);
      discovery.inFlight = false;
    }

    synchronized boolean remove(UUID playerId) {
      PreviewDiscovery discovery = pending.remove(playerId);
      if (discovery == null) {
        return false;
      }
      queued.remove(discovery);
      return true;
    }

    synchronized boolean isPending(PreviewDiscovery discovery) {
      return pending.get(discovery.playerId()) == discovery;
    }

    synchronized int size() {
      return pending.size();
    }

    synchronized void clear() {
      pending.clear();
      queued.clear();
    }

    private synchronized int queuedSize() {
      return queued.size();
    }

    private synchronized PreviewDiscovery claim() {
      while (!queued.isEmpty()) {
        PreviewDiscovery discovery = queued.removeFirst();
        if (pending.get(discovery.playerId()) != discovery) {
          continue;
        }
        discovery.inFlight = true;
        return discovery;
      }
      return null;
    }
  }

  static final class PreviewDiscovery {
    private final Player player;
    private final UUID playerId;
    private boolean forceRescan;
    private boolean inFlight;
    private boolean rerun;
    private boolean rerunForce;

    private PreviewDiscovery(Player player, UUID playerId, boolean forceRescan) {
      this.player = player;
      this.playerId = playerId;
      this.forceRescan = forceRescan;
    }

    Player player() {
      return player;
    }

    UUID playerId() {
      return playerId;
    }

    boolean forceRescan() {
      return forceRescan;
    }
  }

  static final class PreviewFallbackSweep {
    private List<Player> players = List.of();
    private int cursor;
    private int batchSize;

    void begin(Collection<? extends Player> onlinePlayers) {
      players = List.copyOf(onlinePlayers);
      cursor = 0;
      batchSize = players.isEmpty()
          ? 0
          : Math.ceilDiv(players.size(), PREVIEW_FALLBACK_INTERVAL_TICKS);
    }

    int drainBatch(Consumer<Player> action) {
      int end = Math.min(players.size(), cursor + batchSize);
      for (int index = cursor; index < end; index++) {
        action.accept(players.get(index));
      }
      int drained = end - cursor;
      cursor = end;
      return drained;
    }

    int batchSize() {
      return batchSize;
    }

    void clear() {
      players = List.of();
      cursor = 0;
      batchSize = 0;
    }
  }
}
