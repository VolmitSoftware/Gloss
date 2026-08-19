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
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class MenuSessionManager {

  private final Map<Player, SessionHolder> holders = new ConcurrentHashMap<>();

  private final PlayerSnapshotStore<String> openMenus = new PlayerSnapshotStore<>();

  private final ApiClickGuard clickGuard = new ApiClickGuard(Gloss.instance.getLogger(), System::currentTimeMillis,
      ApiClickGuard.DEFAULT_FAULT_LIMIT, ApiClickGuard.DEFAULT_SLOW_MILLIS);

  private SchedulerUtils.TaskHandle debugHitbox, debugPos;

  public PlayerSnapshotStore<String> getOpenMenus() {
    return openMenus;
  }

  public ApiClickGuard getClickGuard() {
    return clickGuard;
  }

  public MenuSessionManager() {
    applyDebugSettings();
    SchedulerUtils.scheduleSyncTask(Gloss.instance, 1L, () -> {
      long tickStart = System.nanoTime();
      holders.forEach((player, holder) -> {
        Runnable tickTask = () -> {
          if (!player.isOnline()) {
            holder.close(HoloCloseReason.QUIT);
            holders.remove(player, holder);
            return;
          }

          if (holder.tick()) {
            holders.remove(player, holder);
          }
        };

        if (!SchedulerUtils.runEntity(Gloss.instance, player, tickTask)) {
          return;
        }
      });
      GlossTelemetry.addTickNanos(System.nanoTime() - tickStart);
    }, false);
    Events.listen(Gloss.instance, PlayerMoveEvent.class, EventPriority.HIGHEST, e -> {
      if (e.isCancelled() || e.getTo() == null) return;
      SessionHolder holder = holders.get(e.getPlayer());
      if (holder == null) return;
      holder.inspectSession(s -> s == null ? null : handleMovement(s, e.getFrom(), e.getTo()));
    });
    Events.listen(Gloss.instance, PlayerDeathEvent.class, EventPriority.MONITOR, e -> {
      SessionHolder holder = holders.get(e.getEntity());
      if (holder == null) return;
      holder.inspectSession(s -> s != null && s.isCloseOnDeath() ? HoloCloseReason.DEATH : null);
    });
    Events.listen(Gloss.instance, PlayerRespawnEvent.class, EventPriority.MONITOR, e -> {
      SessionHolder holder = holders.get(e.getPlayer());
      if (holder == null) return;
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
    });
    Events.listen(Gloss.instance, PlayerTeleportEvent.class, EventPriority.MONITOR, e -> {
      SessionHolder holder = holders.get(e.getPlayer());
      if (holder == null || e.getTo() == null) return;
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
    });
    Events.listen(Gloss.instance, PlayerQuitEvent.class, e -> {
      SessionHolder holder = holders.remove(e.getPlayer());
      if (holder == null) return;
      holder.close(HoloCloseReason.QUIT);
    });
    Events.listen(Gloss.instance, PlayerInteractEvent.class, EventPriority.HIGHEST, this::dispatchClick);
    listenToInventoryPreview();
  }

  private void dispatchClick(PlayerInteractEvent event) {
    if (event.isCancelled()) return;
    Action action = event.getAction();
    if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK
        && action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
    if (event.getHand() == EquipmentSlot.OFF_HAND) return;
    HoloClickTrigger trigger = HoloClickTrigger.fromInteraction(action, event.getPlayer().isSneaking());

    SessionHolder holder = holders.get(event.getPlayer());
    SessionHolder.ClickSnapshot snapshot = holder == null
        ? null
        : holder.snapshotClick(event.getPlayer().getEyeLocation());
    PanelRuntimeManager boardRuntime = Gloss.instance.getPanelRuntime();
    PanelClickTarget boardTarget = boardRuntime == null
        ? null
        : boardRuntime.findClickTarget(event.getPlayer());
    if (snapshot == null && boardTarget == null) return;

    double nearestDistance = snapshot == null
        ? boardTarget.distance()
        : boardTarget == null ? snapshot.distance() : Math.min(snapshot.distance(), boardTarget.distance());
    if (isInteractionObstructed(event.getPlayer(), nearestDistance)) return;

    event.setCancelled(true);
    if (boardTarget != null && (snapshot == null || boardTarget.distance() < snapshot.distance())) {
      try {
        boardTarget.dispatch(trigger);
      } catch (Exception ex) {
        Gloss.logExceptionStack(false, ex, "Board component %s of board %s threw while handling a click from %s.",
            boardTarget.component().getId(), boardTarget.view().definition().id(), event.getPlayer().getName());
      }
      return;
    }

    dispatchPersonalClick(event.getPlayer(), snapshot, trigger);
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
    Vector direction = eye.getDirection().normalize();
    Location sample = eye.clone();
    int previousX = Integer.MIN_VALUE;
    int previousY = Integer.MIN_VALUE;
    int previousZ = Integer.MIN_VALUE;
    for (double traveled = 0.1D; traveled + 1.0E-6D < distance; traveled += 0.1D) {
      sample.setX(eye.getX() + direction.getX() * traveled);
      sample.setY(eye.getY() + direction.getY() * traveled);
      sample.setZ(eye.getZ() + direction.getZ() * traveled);
      int blockX = sample.getBlockX();
      int blockY = sample.getBlockY();
      int blockZ = sample.getBlockZ();
      if (blockX == previousX && blockY == previousY && blockZ == previousZ) {
        continue;
      }
      previousX = blockX;
      previousY = blockY;
      previousZ = blockZ;
      if (!FoliaScheduler.isOwnedByCurrentRegion(sample)) {
        return true;
      }
      if (!world.getBlockAt(blockX, blockY, blockZ).isPassable()) {
        return true;
      }
    }
    return false;
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
      Gloss.logExceptionStack(false, ex, "Menu component %s of menu %s threw while handling a click from %s.",
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
    SessionHolder holder = holders.get(player);
    if (request.mode() == NavigationMode.CLOSE) {
      return holder != null && holder.closeSession(false, HoloCloseReason.CLOSED_BY_COMMAND)
          ? NavigationResult.APPLIED
          : NavigationResult.NO_HISTORY;
    }
    if (!GlossConfig.current().menus().enabled()) {
      return NavigationResult.DENIED;
    }

    String target = switch (request.mode()) {
      case PUSH, REPLACE -> request.target();
      case BACK -> holder == null ? null : holder.lastSessionId();
      case HOME -> holder == null ? null : holder.rootSessionId();
      case CLOSE -> null;
    };
    if (target == null) {
      return NavigationResult.NO_HISTORY;
    }

    MenuDefinitionData menu = Gloss.instance.getConfigManager().get(target).orElse(null);
    if (menu == null) {
      player.sendMessage(Gloss.instance.getLocalization().legacy(
          GlossMessages.MENU_UNAVAILABLE,
          MessageArgs.builder().untrusted("menu", target).build()
      ));
      return NavigationResult.NOT_FOUND;
    }
    if (!player.hasPermission("gloss.open." + menu.getId())) {
      player.sendMessage(Gloss.instance.getLocalization().legacy(
          GlossMessages.MENU_PERMISSION_DENIED,
          MessageArgs.builder().untrusted("menu", menu.getId()).build()
      ));
      return NavigationResult.DENIED;
    }
    if (!ApiEvents.fireOpen(player, menu.getId(), null)) {
      return NavigationResult.DENIED;
    }

    SessionHolder activeHolder = holder == null ? holders.computeIfAbsent(player, this::newHolder) : holder;
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

    holders.computeIfAbsent(p, this::newHolder).openSession(menu, handle);
    return true;
  }

  public boolean hasMenuSession(Player p) {
    SessionHolder holder = holders.get(p);
    return holder != null && holder.hasSession();
  }

  public boolean moveSession(Player p) {
    SessionHolder holder = holders.get(p);
    return holder != null && holder.moveSession(p.getLocation());
  }

  public boolean destroySessionFor(Player p, ApiMenuHandle handle, HoloCloseReason reason) {
    SessionHolder holder = holders.get(p);
    return holder != null && holder.closeSessionOf(handle, reason);
  }

  public void addPreviewSession(Player p, ContainerPreview session) {
    holders.computeIfAbsent(p, this::newHolder).openPreview(session);
  }

  public boolean hasPreviewSession(Player p) {
    SessionHolder holder = holders.get(p);
    return holder != null && holder.hasPreview();
  }

  public boolean destroySession(Player p, boolean history) {
    return destroySession(p, history, HoloCloseReason.CLOSED_BY_COMMAND);
  }

  public boolean destroySession(Player p, boolean history, HoloCloseReason reason) {
    SessionHolder holder = holders.get(p);
    if (holder == null) return false;
    return holder.closeSession(history, reason);
  }

  public void destroyAll() {
    holders.values().forEach(holder -> holder.close(HoloCloseReason.GLOSS_SHUTDOWN));
    holders.clear();
    openMenus.clear();
  }

  private SessionHolder newHolder(Player player) {
    return new SessionHolder(player, openMenus);
  }

  public void destroyAllType(String id, Consumer<Player> consumer) {
    holders.forEach((player, holder) -> {
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
    holders.forEach((player, holder) -> {
      Runnable refreshTask = holder::refreshVisuals;
      SchedulerUtils.runEntity(Gloss.instance, player, refreshTask);
    });
  }

  public void closeAllPreviews() {
    holders.forEach((player, holder) -> {
      Runnable closeTask = holder::closePreview;
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
      debugHitbox = SchedulerUtils.scheduleSyncTask(Gloss.instance, 2L, () -> holders.forEach((player, holder) -> {
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
      debugPos = SchedulerUtils.scheduleSyncTask(Gloss.instance, 2L, () -> holders.forEach((player, holder) -> {
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

  private void listenToInventoryPreview() {
    SchedulerUtils.scheduleSyncTask(Gloss.instance, 1L, () -> Bukkit.getOnlinePlayers().forEach(player -> {
      Runnable previewTask = () -> managePreviewEvents(player);
      if (!SchedulerUtils.runEntity(Gloss.instance, player, previewTask)) {
        SessionHolder holder = holders.get(player);
        if (holder != null) {
          holder.closePreview();
        }
      }
    }), false);
  }

  private void managePreviewEvents(Player p) {
    try {
      PreviewTarget target = getLookedAtPreviewTarget(p);
      SessionHolder holder = holders.get(p);
      if (target == null) {
        if (holder != null) {
          holder.closePreview();
        }
        return;
      }

      if (holder == null) {
        holder = holders.computeIfAbsent(p, this::newHolder);
      }

      SessionHolder finalHolder = holder;
      holder.onPreview(preview -> {
        if (preview == null) {
          createNewPreviewSession(target, p);
          return;
        }

        if (!target.matches(preview)) {
          finalHolder.closePreview();
          createNewPreviewSession(target, p);
        }
      });
    } catch (Exception ex) {
      Gloss.logExceptionStack(false, ex, "Failed to manage inventory preview for %s.", p.getName());
    }
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

  private PreviewTarget getLookedAtPreviewTarget(Player player) {
    if (!ContainerPreviewAccess.isEnabled()) {
      return null;
    }
    Location eyeLocation = player.getEyeLocation();
    World world = eyeLocation.getWorld();
    if (world == null) {
      return null;
    }
    Vector direction = eyeLocation.getDirection();
    RayTraceResult blockResult = world.rayTraceBlocks(
        eyeLocation,
        direction,
        GlossConfig.current().previews().lookDistance(),
        FluidCollisionMode.NEVER,
        true
    );
    PreviewTarget blockTarget = null;
    Block targetBlock = blockResult == null ? null : blockResult.getHitBlock();
    if (targetBlock != null && targetBlock.getType() != Material.AIR && isPreviewBlockType(targetBlock.getType())) {
      blockTarget = PreviewTarget.block(targetBlock);
    }
    RayTraceResult entityResult = world.rayTraceEntities(
        eyeLocation,
        direction,
        GlossConfig.current().previews().lookDistance(),
        0.35D,
        this::isPreviewEntity
    );
    Entity targetEntity = entityResult == null ? null : entityResult.getHitEntity();
    if (targetEntity == null) {
      return blockTarget;
    }
    double blockDistance = hitDistanceSquared(eyeLocation, blockResult);
    double entityDistance = hitDistanceSquared(eyeLocation, entityResult);
    if (targetBlock != null && targetBlock.getType() != Material.AIR && blockDistance + 0.01D < entityDistance) {
      return blockTarget;
    }
    return PreviewTarget.entity(targetEntity);
  }

  private void createNewPreviewSession(PreviewTarget target, Player p) {
    ContainerPreviewAccess.ViewerAccess access = ContainerPreviewAccess.capture(p);
    if (target.block() != null) {
      createNewBlockPreviewSession(target.block(), p, access);
      return;
    }
    if (target.entity() != null) {
      createNewEntityPreviewSession(target.entity(), p, access);
    }
  }

  private void createNewBlockPreviewSession(Block b, Player p, ContainerPreviewAccess.ViewerAccess access) {
    Runnable createTask = () -> {
      if (b.getType() == Material.AIR) {
        return;
      }
      boolean canOpen = ContainerPreviewAccess.canOpen(p, b, access);
      if (!canOpen) {
        ContainerPreview lockedSession = ContainerPreview.locked(b, p);
        openPreviewIfCurrent(PreviewTarget.block(b), p, lockedSession);
        return;
      }
      if (isEnderChestDocument(b)) {
        Vector center = b.getLocation().toVector().add(new Vector(0.5D, 0.5D, 0.5D));
        Runnable buildTask = () -> {
          ContainerPreview newSession = ContainerPreview.forEnderChest(b, p, center);
          openPreviewIfCurrent(PreviewTarget.block(b), p, newSession);
        };
        if (!SchedulerUtils.runEntity(Gloss.instance, p, buildTask)) {
          SessionHolder holder = holders.get(p);
          if (holder != null) {
            holder.closePreview();
          }
        }
        return;
      }
      Runnable buildTask = () -> {
        ContainerPreview newSession = ContainerPreview.forBlock(b, p);
        openPreviewIfCurrent(PreviewTarget.block(b), p, newSession);
      };
      buildTask.run();
    };

    if (!FoliaScheduler.runRegion(Gloss.instance, b.getLocation(), createTask)
        && !FoliaScheduler.isFolia(Gloss.instance.getServer())) {
      createTask.run();
    }
  }

  private void createNewEntityPreviewSession(Entity entity, Player p, ContainerPreviewAccess.ViewerAccess access) {
    Runnable createTask = () -> {
      if (!entity.isValid() || !isPreviewEntity(entity)) {
        return;
      }
      ContainerPreview newSession = ContainerPreviewAccess.canOpen(p, entity, access)
          ? ContainerPreview.forEntity(entity, p)
          : ContainerPreview.locked(entity, p);
      openPreviewIfCurrent(PreviewTarget.entity(entity), p, newSession);
    };

    if (!SchedulerUtils.runEntity(Gloss.instance, entity, createTask)) {
      SessionHolder holder = holders.get(p);
      if (holder != null) {
        holder.closePreview();
      }
    }
  }

  private void openPreviewIfCurrent(PreviewTarget target, Player p, ContainerPreview newSession) {
    if (newSession == null) {
      return;
    }
    Runnable openTask = () -> {
      if (!newSession.canView() || !isStillLookingAt(p, target)) {
        newSession.close();
        return;
      }
      addPreviewSession(p, newSession);
    };
    if (!SchedulerUtils.runEntity(Gloss.instance, p, openTask)) {
      openTask.run();
    }
  }

  private boolean isStillLookingAt(Player player, PreviewTarget target) {
    PreviewTarget current = getLookedAtPreviewTarget(player);
    return target.matches(current);
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

  private record PreviewTarget(Block block, Entity entity) {

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
}
