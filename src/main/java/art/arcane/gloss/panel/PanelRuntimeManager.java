package art.arcane.gloss.panel;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.action.NavigationResult;
import art.arcane.gloss.menu.components.ClickableComponent;
import art.arcane.gloss.menu.components.MenuComponent;
import art.arcane.volmlib.util.bukkit.Events;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PanelRuntimeManager implements PanelServiceListener {
  private static final long SHUTDOWN_TIMEOUT_SECONDS = 10L;

  private static final double CHUNK_SIZE = 16.0D;

  /**
   * Horizontal padding added to a cached candidate query, in blocks: the longest distance between
   * two points of one chunk. A viewer reuses its candidate list for as long as it stays in the
   * chunk it queried from, so the query has to cover every position that chunk contains — with the
   * padding the cached list is always a superset of the true in-range set, and the authoritative
   * per-panel range test below still decides membership.
   */
  private static final double CHUNK_QUERY_PADDING = CHUNK_SIZE * Math.sqrt(2.0D);

  private final Gloss plugin;
  private final PanelService boards;
  private final Object definitionLock = new Object();
  private final PanelSpatialIndex effectiveIndex = new PanelSpatialIndex();
  private final Map<UUID, PanelDefinition> definitions = new ConcurrentHashMap<>();
  private final Map<UUID, ViewerState> viewers = new ConcurrentHashMap<>();
  private final Map<UUID, PanelPreview> previews = new ConcurrentHashMap<>();
  private final Map<UUID, PlayerPresence> playerPresences = new ConcurrentHashMap<>();
  private final Set<UUID> tickingViewers = ConcurrentHashMap.newKeySet();
  private final Map<UUID, PanelFollowPose> pendingFollowPoses = new ConcurrentHashMap<>();
  private final Map<UUID, PanelFollowPose> followPoses = new ConcurrentHashMap<>();
  private final VisibleBoardCounter visibleBoards = new VisibleBoardCounter();
  private final SchedulerUtils.TaskHandle tickTask;
  private final Events quitListener;
  private final Events joinListener;
  private final Events moveListener;
  private final Events teleportListener;
  private final Events respawnListener;
  private final Events worldListener;

  private volatile boolean running = true;
  private volatile Map<UUID, Set<UUID>> followedBoardsByTarget = Map.of();

  public PanelRuntimeManager(Gloss plugin, PanelService boards) {
    this.plugin = plugin;
    this.boards = boards;
    replaceDefinitions(boards.subscribeAndSnapshot(this));
    this.quitListener = Events.listen(plugin, PlayerQuitEvent.class, EventPriority.MONITOR,
        event -> {
          UUID playerId = event.getPlayer().getUniqueId();
          playerPresences.remove(playerId);
          pendingFollowPoses.remove(playerId);
          closeViewer(event.getPlayer());
        });
    this.joinListener = Events.listen(plugin, PlayerJoinEvent.class, EventPriority.MONITOR,
        event -> recordPresence(event.getPlayer(), event.getPlayer().getLocation()));
    this.moveListener = Events.listen(plugin, PlayerMoveEvent.class, EventPriority.MONITOR,
        event -> {
          if (!event.isCancelled() && event.getTo() != null) {
            recordPresence(event.getPlayer(), event.getTo());
          }
        });
    this.teleportListener = Events.listen(plugin, PlayerTeleportEvent.class, EventPriority.MONITOR,
        event -> {
          if (!event.isCancelled() && event.getTo() != null) {
            recordPresence(event.getPlayer(), event.getTo());
          }
        });
    this.respawnListener = Events.listen(plugin, PlayerRespawnEvent.class, EventPriority.MONITOR,
        event -> recordPresence(event.getPlayer(), event.getRespawnLocation()));
    this.worldListener = Events.listen(plugin, PlayerChangedWorldEvent.class, EventPriority.MONITOR,
        event -> recordPresence(event.getPlayer(), event.getPlayer().getLocation()));
    captureOnlinePlayers();
    this.tickTask = SchedulerUtils.scheduleSyncTask(plugin, 1L, this::scheduleTick, false);
  }

  public int visibleBoardCount() {
    return visibleBoards.get();
  }

  public Optional<PanelDefinition> effectiveBoard(UUID boardUuid) {
    return effectiveIndex.get(Objects.requireNonNull(boardUuid, "boardUuid"));
  }

  public List<PanelDefinition> queryEffective(UUID worldUuid, double x, double z, double radius) {
    return effectiveIndex.query(worldUuid, x, z, radius);
  }

  public Optional<PanelFollowPose> followPose(UUID targetPlayerUuid) {
    return Optional.ofNullable(followPoses.get(Objects.requireNonNull(targetPlayerUuid, "targetPlayerUuid")));
  }

  public PanelClickTarget findClickTarget(Player player) {
    if (!running || player == null || !player.isOnline()) {
      return null;
    }
    ViewerState state = viewers.get(player.getUniqueId());
    return state == null ? null : state.findClickTarget();
  }

  public void previewBoard(Player viewer, PanelDefinition definition) {
    PanelDefinition requiredDefinition = Objects.requireNonNull(definition, "definition");
    PanelTransform effectiveTransform = requiredDefinition.transform();
    if (requiredDefinition.follow().mode() == PanelFollowMode.PLAYER) {
      effectiveTransform = effectiveIndex.get(requiredDefinition.uuid())
          .map(PanelDefinition::transform)
          .orElse(effectiveTransform);
    }
    previewBoard(viewer, requiredDefinition, effectiveTransform);
  }

  public void previewBoard(Player viewer, PanelDefinition definition, PanelTransform effectiveTransform) {
    Player requiredViewer = Objects.requireNonNull(viewer, "viewer");
    PanelPreview preview = new PanelPreview(definition, effectiveTransform);
    previews.put(requiredViewer.getUniqueId(), preview);
    capturePresence(requiredViewer);
  }

  public void clearBoardPreview(Player viewer, UUID boardUuid) {
    Player requiredViewer = Objects.requireNonNull(viewer, "viewer");
    UUID requiredBoardUuid = Objects.requireNonNull(boardUuid, "boardUuid");
    previews.computeIfPresent(requiredViewer.getUniqueId(), (ignored, preview) ->
        preview.definition().uuid().equals(requiredBoardUuid) ? null : preview);
  }

  public void refreshMenu(String menuId) {
    for (ViewerState state : viewers.values()) {
      Runnable refresh = () -> state.refreshMenu(menuId);
      SchedulerUtils.runEntity(plugin, state.player, refresh);
    }
  }

  public void refreshVisuals() {
    for (ViewerState state : viewers.values()) {
      Runnable refresh = state::refreshVisuals;
      SchedulerUtils.runEntity(plugin, state.player, refresh);
    }
  }

  public void shutdown() {
    if (!running) {
      return;
    }
    running = false;
    boards.removeListener(this);
    tickTask.cancel();
    quitListener.unregister();
    joinListener.unregister();
    moveListener.unregister();
    teleportListener.unregister();
    respawnListener.unregister();
    worldListener.unregister();
    closeViewersForShutdown();
    viewers.clear();
    previews.clear();
    playerPresences.clear();
    tickingViewers.clear();
    synchronized (definitionLock) {
      pendingFollowPoses.clear();
      followPoses.clear();
      definitions.clear();
      followedBoardsByTarget = Map.of();
      effectiveIndex.replaceAll(List.of());
    }
    visibleBoards.closeEpoch();
  }

  @Override
  public void boardCreated(PanelDefinition board) {
    publishDefinition(board);
  }

  @Override
  public void boardUpdated(PanelDefinition previous, PanelDefinition updated) {
    publishDefinition(updated);
  }

  @Override
  public void boardDeleted(PanelDefinition board) {
    synchronized (definitionLock) {
      if (!running) {
        return;
      }
      definitions.remove(board.uuid());
      effectiveIndex.remove(board.uuid());
      previews.entrySet().removeIf(entry -> entry.getValue().definition().uuid().equals(board.uuid()));
      refreshFollowerIndex();
      if (board.follow().targetPlayerUuid() != null) {
        removeUnusedFollowPose(board.follow().targetPlayerUuid());
      }
    }
  }

  @Override
  public void boardsReloaded(PanelLoadResult result, List<PanelDefinition> loadedBoards) {
    replaceDefinitions(loadedBoards);
  }

  private void scheduleTick() {
    if (!running) {
      return;
    }
    try {
      applyPendingFollowPoses();
    } catch (RuntimeException failure) {
      Gloss.logExceptionStackThrottled(false, "panel-follow-sampling", failure,
          "Failed to sample persistent panel follow targets.");
    }
    if (idle()) {
      return;
    }

    double maximumRange = boards.maximumViewRange();
    for (PlayerPresence presence : playerPresences.values()) {
      UUID playerId = presence.player().getUniqueId();
      ViewerState state = viewers.get(playerId);
      boolean active = previews.containsKey(playerId) || state != null && state.anyViews;
      if (!active && !effectiveIndex.hasCandidate(
          presence.worldUuid(), presence.x(), presence.z(), maximumRange)) {
        continue;
      }
      scheduleViewer(presence.player());
    }
    for (ViewerState state : viewers.values()) {
      if (state.anyViews) {
        scheduleViewer(state.player);
      }
    }
    for (UUID playerId : previews.keySet()) {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null) {
        scheduleViewer(player);
      }
    }
  }

  /**
   * True when no viewer can have anything to do this tick: nothing placed, nothing being previewed
   * in the editor, and no viewer state holding open views that would need closing. A server with no
   * panels at all therefore pays nothing per player per tick.
   */
  private boolean idle() {
    return effectiveIndex.isEmpty() && previews.isEmpty() && visibleBoards.get() == 0;
  }

  private void applyPendingFollowPoses() {
    if (pendingFollowPoses.isEmpty()) {
      return;
    }
    Map<UUID, PanelFollowPose> changed = new HashMap<>();
    for (Map.Entry<UUID, PanelFollowPose> entry : pendingFollowPoses.entrySet()) {
      if (pendingFollowPoses.remove(entry.getKey(), entry.getValue())) {
        changed.put(entry.getKey(), entry.getValue());
      }
    }
    if (changed.isEmpty()) {
      return;
    }

    synchronized (definitionLock) {
      if (!running) {
        return;
      }
      Map<UUID, Set<UUID>> followers = followedBoardsByTarget;
      List<PanelDefinition> resolved = new ArrayList<>();
      for (Map.Entry<UUID, PanelFollowPose> entry : changed.entrySet()) {
        Set<UUID> boardUuids = followers.get(entry.getKey());
        if (boardUuids == null) {
          continue;
        }
        followPoses.put(entry.getKey(), entry.getValue());
        for (UUID boardUuid : boardUuids) {
          PanelDefinition board = definitions.get(boardUuid);
          if (board == null || board.follow().mode() != PanelFollowMode.PLAYER
              || !entry.getKey().equals(board.follow().targetPlayerUuid())) {
            continue;
          }
          resolved.add(board.withTransform(PanelFollowTransform.resolve(board, entry.getValue())));
        }
      }
      effectiveIndex.upsertAll(resolved);
    }
  }

  private void scheduleViewer(Player player) {
    UUID playerId = player.getUniqueId();
    if (!tickingViewers.add(playerId)) {
      return;
    }
    Runnable tick = () -> {
      try {
        if (!running || !player.isOnline()) {
          closeViewer(player);
          return;
        }
        viewers.computeIfAbsent(playerId, ignored -> new ViewerState(player)).tick();
      } catch (RuntimeException failure) {
        Gloss.logExceptionStackThrottled(false, "panel-viewer-update", failure,
            "Failed to update persistent panels for %s.", player.getName());
        closeViewer(player);
      } finally {
        tickingViewers.remove(playerId);
      }
    };
    Runnable retired = () -> tickingViewers.remove(playerId);
    if (!FoliaScheduler.runEntity(plugin, player, tick, 0L, retired)) {
      tickingViewers.remove(playerId);
    }
  }

  private void captureOnlinePlayers() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      capturePresence(player);
    }
  }

  private void capturePresence(Player player) {
    Runnable capture = () -> {
      if (running && player.isOnline()) {
        recordPresence(player, player.getLocation());
      }
    };
    FoliaScheduler.runEntity(plugin, player, capture, 0L,
        () -> playerPresences.remove(player.getUniqueId()));
  }

  private void recordPresence(Player player, Location location) {
    World world = location.getWorld();
    UUID playerId = player.getUniqueId();
    if (world == null) {
      playerPresences.remove(playerId);
      return;
    }
    playerPresences.put(playerId,
        new PlayerPresence(player, world.getUID(), location.getX(), location.getZ()));
    if (followedBoardsByTarget.containsKey(playerId)) {
      pendingFollowPoses.put(playerId, PanelFollowPose.from(location));
    }
  }

  private void publishDefinition(PanelDefinition board) {
    synchronized (definitionLock) {
      if (!running) {
        return;
      }
      PanelDefinition previous = definitions.put(board.uuid(), board);
      if (board.follow().mode() == PanelFollowMode.NONE) {
        effectiveIndex.upsert(board);
      } else {
        PanelFollowPose pose = followPoses.get(board.follow().targetPlayerUuid());
        if (pose == null) {
          effectiveIndex.remove(board.uuid());
        } else {
          effectiveIndex.upsert(board.withTransform(PanelFollowTransform.resolve(board, pose)));
        }
      }
      refreshFollowerIndex();
      if (previous != null && previous.follow().targetPlayerUuid() != null
          && !Objects.equals(previous.follow().targetPlayerUuid(), board.follow().targetPlayerUuid())) {
        removeUnusedFollowPose(previous.follow().targetPlayerUuid());
      }
      if (board.follow().targetPlayerUuid() != null) {
        requestFollowSample(board.follow().targetPlayerUuid());
      }
    }
  }

  private void replaceDefinitions(List<PanelDefinition> loadedBoards) {
    synchronized (definitionLock) {
      if (!running) {
        return;
      }
      definitions.clear();
      List<PanelDefinition> effectiveBoards = new ArrayList<>(loadedBoards.size());
      for (PanelDefinition board : loadedBoards) {
        definitions.put(board.uuid(), board);
        if (board.follow().mode() == PanelFollowMode.NONE) {
          effectiveBoards.add(board);
          continue;
        }
        PanelFollowPose pose = followPoses.get(board.follow().targetPlayerUuid());
        if (pose != null) {
          effectiveBoards.add(board.withTransform(PanelFollowTransform.resolve(board, pose)));
        }
      }
      effectiveIndex.replaceAll(effectiveBoards);
      refreshFollowerIndex();
      requestFollowSamples();
    }
  }

  static Map<UUID, Set<UUID>> indexFollowers(Collection<PanelDefinition> definitions) {
    Map<UUID, Set<UUID>> mutable = new HashMap<>();
    for (PanelDefinition board : definitions) {
      if (board.follow().mode() != PanelFollowMode.PLAYER) {
        continue;
      }
      mutable.computeIfAbsent(board.follow().targetPlayerUuid(), ignored -> new HashSet<>())
          .add(board.uuid());
    }
    Map<UUID, Set<UUID>> frozen = new HashMap<>(mutable.size());
    for (Map.Entry<UUID, Set<UUID>> entry : mutable.entrySet()) {
      frozen.put(entry.getKey(), Set.copyOf(entry.getValue()));
    }
    return Map.copyOf(frozen);
  }

  private void refreshFollowerIndex() {
    Map<UUID, Set<UUID>> followers = indexFollowers(definitions.values());
    followedBoardsByTarget = followers;
    followPoses.keySet().removeIf(targetId -> !followers.containsKey(targetId));
    pendingFollowPoses.keySet().removeIf(targetId -> !followers.containsKey(targetId));
  }

  private void requestFollowSamples() {
    for (UUID targetId : followedBoardsByTarget.keySet()) {
      requestFollowSample(targetId);
    }
  }

  private void requestFollowSample(UUID targetId) {
    SchedulerUtils.runGlobal(plugin, () -> {
      if (!running || !followedBoardsByTarget.containsKey(targetId)) {
        return;
      }
      Player target = Bukkit.getPlayer(targetId);
      if (target != null) {
        capturePresence(target);
      }
    });
  }

  private void removeUnusedFollowPose(UUID targetId) {
    if (!followedBoardsByTarget.containsKey(targetId)) {
      followPoses.remove(targetId);
    }
  }

  private void closeViewer(Player player) {
    ViewerState state = viewers.remove(player.getUniqueId());
    previews.remove(player.getUniqueId());
    if (state != null) {
      state.close();
    }
  }

  private void closeViewersForShutdown() {
    List<ViewerState> snapshot = List.copyOf(viewers.values());
    CountDownLatch closed = new CountDownLatch(snapshot.size());
    for (ViewerState state : snapshot) {
      AtomicBoolean completed = new AtomicBoolean();
      Runnable close = () -> {
        if (!completed.compareAndSet(false, true)) {
          return;
        }
        try {
          state.close();
          viewers.remove(state.player.getUniqueId(), state);
        } catch (RuntimeException failure) {
          Gloss.logExceptionStack(false, failure,
              "Failed to close persistent panel views for %s during shutdown.", state.player.getName());
        } finally {
          tickingViewers.remove(state.player.getUniqueId());
          closed.countDown();
        }
      };
      if (!FoliaScheduler.runEntity(plugin, state.player, close, 0L, close)) {
        close.run();
      }
    }
    try {
      if (!closed.await(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        Gloss.log(java.util.logging.Level.WARNING,
            "Timed out waiting for persistent panel viewer tasks to close during shutdown.");
      }
    } catch (InterruptedException interruption) {
      Thread.currentThread().interrupt();
    }
  }

  private boolean canView(Player player, PanelDefinition board) {
    PanelVisibility visibility = board.visibility();
    return switch (visibility.mode()) {
      case PUBLIC -> true;
      case PERMISSION -> player.hasPermission(visibility.viewPermission());
      case HIDDEN -> false;
    };
  }

  private boolean canInteract(Player player, PanelDefinition board) {
    String permission = board.visibility().interactPermission();
    return permission == null || player.hasPermission(permission);
  }

  private final class ViewerState {
    private final Player player;
    private final Map<UUID, PanelViewSession> views = new HashMap<>();
    private final Map<UUID, Long> unavailable = new HashMap<>();
    private final Set<UUID> dismissed = new HashSet<>();
    private final long visibleBoardEpoch;

    private List<PanelDefinition> cachedCandidates = List.of();
    private UUID cachedWorld;
    private long cachedGeneration = -1L;
    private double cachedRange = -1.0D;
    private int cachedChunkX;
    private int cachedChunkZ;
    private boolean closed;

    private volatile boolean anyViews;

    private ViewerState(Player player) {
      this.player = player;
      this.visibleBoardEpoch = visibleBoards.epoch();
    }

    private synchronized void tick() {
      if (closed || !running) {
        close();
        return;
      }
      Location location = player.getLocation();
      recordPresence(player, location);
      World world = location.getWorld();
      if (world == null) {
        closeViews();
        return;
      }

      List<PanelDefinition> candidates = candidates(world, location);
      PanelPreview preview = previews.get(player.getUniqueId());
      if (candidates.isEmpty() && preview == null && views.isEmpty()
          && dismissed.isEmpty() && unavailable.isEmpty()) {
        return;
      }
      Map<UUID, PanelDefinition> effectiveCandidates = new LinkedHashMap<>(candidates.size() + 1);
      for (PanelDefinition candidate : candidates) {
        effectiveCandidates.put(candidate.uuid(), candidate);
      }
      if (preview != null) {
        PanelTransform previewTransform = preview.effectiveTransform();
        PanelDefinition previewDefinition = preview.definition();
        if (previewDefinition.follow().mode() == PanelFollowMode.PLAYER) {
          PanelFollowPose pose = followPoses.get(previewDefinition.follow().targetPlayerUuid());
          if (pose != null) {
            previewTransform = PanelFollowTransform.resolve(previewDefinition, pose);
          }
        }
        effectiveCandidates.put(previewDefinition.uuid(), previewDefinition.withTransform(previewTransform));
      }
      Set<UUID> inRange = new HashSet<>();
      for (PanelDefinition effective : effectiveCandidates.values()) {
        boolean editing = preview != null && preview.definition().uuid().equals(effective.uuid());
        PanelDefinition definition = editing ? preview.definition() : definitions.get(effective.uuid());
        if (definition == null
            || !effective.transform().worldUuid().equals(world.getUID())
            || (!editing && !canView(player, definition))) {
          continue;
        }
        double range = definition.visibility().viewRange();
        if (!editing && PanelPlacement.distanceSquared(effective, location) > range * range) {
          continue;
        }
        inRange.add(definition.uuid());
        if (editing) {
          dismissed.remove(definition.uuid());
          unavailable.remove(definition.uuid());
        }
        if (dismissed.contains(definition.uuid())) {
          continue;
        }
        Long failedRevision = unavailable.get(definition.uuid());
        if (failedRevision != null && failedRevision == definition.revision()) {
          continue;
        }
        unavailable.remove(definition.uuid());
        PanelViewSession view = views.get(definition.uuid());
        if (view == null) {
          view = new PanelViewSession(new PanelViewOptions(
              definition,
              effective.transform(),
              player,
              plugin.getMenuCatalog(),
              this::dismiss
          ));
          NavigationResult openResult = view.open();
          if (openResult != NavigationResult.APPLIED) {
            view.close();
            if (openResult == NavigationResult.NOT_FOUND) {
              unavailable.put(definition.uuid(), definition.revision());
            }
            continue;
          }
          views.put(definition.uuid(), view);
          anyViews = true;
          visibleBoards.add(visibleBoardEpoch, 1);
        } else {
          NavigationResult updateResult = view.update(definition, effective.transform());
          if (updateResult != NavigationResult.APPLIED) {
            views.remove(definition.uuid(), view);
            anyViews = !views.isEmpty();
            visibleBoards.add(visibleBoardEpoch, -1);
            if (updateResult == NavigationResult.NOT_FOUND) {
              unavailable.put(definition.uuid(), definition.revision());
            }
            continue;
          }
        }
        view.tick();
      }

      Iterator<Map.Entry<UUID, PanelViewSession>> viewIterator = views.entrySet().iterator();
      while (viewIterator.hasNext()) {
        Map.Entry<UUID, PanelViewSession> entry = viewIterator.next();
        if (!inRange.contains(entry.getKey())) {
          entry.getValue().close();
          viewIterator.remove();
          visibleBoards.add(visibleBoardEpoch, -1);
        }
      }
      anyViews = !views.isEmpty();
      dismissed.removeIf(boardId -> !inRange.contains(boardId));
      unavailable.keySet().removeIf(boardId -> !inRange.contains(boardId));
    }

    /**
     * The panels this viewer could possibly see, reused for as long as it stays in the chunk it
     * queried from and no index change intersected that chunk window. The query is a candidate
     * prefilter — the caller still range-tests every entry against that panel's own view range — so
     * a padded, slightly over-broad list produces exactly the same membership.
     */
    private List<PanelDefinition> candidates(World world, Location location) {
      double queryRange = boards.maximumViewRange();
      if (queryRange <= 0.0D) {
        cachedCandidates = List.of();
        cachedGeneration = -1L;
        return List.of();
      }
      int chunkX = (int) Math.floor(location.getX() / CHUNK_SIZE);
      int chunkZ = (int) Math.floor(location.getZ() / CHUNK_SIZE);
      long generation = effectiveIndex.generation();
      boolean sameQueryWindow = queryRange == cachedRange && chunkX == cachedChunkX
          && chunkZ == cachedChunkZ && world.getUID().equals(cachedWorld);
      if (sameQueryWindow) {
        if (generation == cachedGeneration || !effectiveIndex.changedSince(
            cachedGeneration, generation, world.getUID(), location.getX(), location.getZ(),
            queryRange + CHUNK_QUERY_PADDING)) {
          cachedGeneration = generation;
          return cachedCandidates;
        }
      }
      PanelSpatialIndex.QuerySnapshot snapshot = effectiveIndex.querySnapshot(
          world.getUID(), location.getX(), location.getZ(), queryRange + CHUNK_QUERY_PADDING);
      cachedCandidates = snapshot.boards();
      cachedWorld = world.getUID();
      cachedGeneration = snapshot.generation();
      cachedRange = queryRange;
      cachedChunkX = chunkX;
      cachedChunkZ = chunkZ;
      return cachedCandidates;
    }

    private PanelClickTarget findClickTarget() {
      return anyViews ? nearestClickTarget() : null;
    }

    private synchronized PanelClickTarget nearestClickTarget() {
      if (views.isEmpty()) {
        return null;
      }
      Location eye = player.getEyeLocation();
      Vector origin = eye.toVector();
      Vector direction = eye.getDirection();
      PanelClickTarget nearest = null;
      PanelPreview preview = previews.get(player.getUniqueId());
      for (PanelViewSession view : views.values()) {
        boolean editing = preview != null && preview.definition().uuid().equals(view.definition().uuid());
        PanelDefinition definition = editing
            ? preview.definition()
            : definitions.get(view.definition().uuid());
        if (definition == null || definition.revision() != view.definition().revision()) {
          continue;
        }
        double interactionRange = editing
            ? Double.POSITIVE_INFINITY
            : definition.visibility().interactionRange();
        PanelTransform currentEffective = editing
            ? (definition.follow().mode() == PanelFollowMode.PLAYER
                ? followPose(definition.follow().targetPlayerUuid())
                    .map(pose -> PanelFollowTransform.resolve(preview.definition(), pose))
                    .orElse(preview.effectiveTransform())
                : preview.effectiveTransform())
            : effectiveIndex.get(definition.uuid()).map(PanelDefinition::transform).orElse(null);
        if ((!editing && (!canView(player, definition) || !canInteract(player, definition)))
            || currentEffective == null
            || !view.effectiveTransform().equals(currentEffective)
            || PanelPlacement.distanceSquared(view.effectiveTransform(), eye) > interactionRange * interactionRange) {
          continue;
        }
        MenuSession session = view.session();
        if (session == null) {
          continue;
        }
        for (MenuComponent<?> component : session.getComponents()) {
          if (!(component instanceof ClickableComponent<?> clickable)) {
            continue;
          }
          OptionalDouble distance = clickable.intersectionDistance(origin, direction);
          if (distance.isEmpty() || distance.getAsDouble() > interactionRange) {
            continue;
          }
          if (nearest == null || distance.getAsDouble() < nearest.distance()) {
            nearest = new PanelClickTarget(view, clickable, distance.getAsDouble());
          }
        }
      }
      return nearest;
    }

    private void dismiss(PanelViewSession view) {
      dismissed.add(view.definition().uuid());
      closeView(view.definition().uuid());
    }

    private synchronized void refreshMenu(String menuId) {
      unavailable.clear();
      for (PanelViewSession view : List.copyOf(views.values())) {
        if (view.currentMenuId() != null && view.currentMenuId().equals(menuId)) {
          boolean refreshed = plugin.getMenuCatalog().exists(view.currentMenuId())
              ? view.reloadCurrent()
              : view.returnHome();
          if (!refreshed) {
            UUID boardId = view.definition().uuid();
            unavailable.put(boardId, view.definition().revision());
            closeView(boardId);
          }
        }
      }
    }

    private synchronized void refreshVisuals() {
      for (PanelViewSession view : views.values()) {
        MenuSession session = view.session();
        if (session == null) {
          continue;
        }
        List<MenuComponent<?>> openComponents = session.getComponents().stream()
            .filter(MenuComponent::isOpen)
            .toList();
        openComponents.forEach(MenuComponent::close);
        session.refreshScale();
        openComponents.forEach(MenuComponent::open);
      }
    }

    private synchronized void close() {
      if (closed) {
        return;
      }
      closed = true;
      closeViews();
      dismissed.clear();
      unavailable.clear();
    }

    private void closeViews() {
      int count = views.size();
      for (PanelViewSession view : views.values()) {
        view.close();
      }
      views.clear();
      anyViews = false;
      if (count > 0) {
        visibleBoards.add(visibleBoardEpoch, -count);
      }
    }

    private void closeView(UUID boardId) {
      PanelViewSession view = views.remove(boardId);
      if (view != null) {
        anyViews = !views.isEmpty();
        view.close();
        visibleBoards.add(visibleBoardEpoch, -1);
      }
    }
  }

  static final class VisibleBoardCounter {
    private long epoch = 1L;
    private int count;
    private boolean open = true;

    synchronized long epoch() {
      return open ? epoch : -1L;
    }

    synchronized int get() {
      return count;
    }

    synchronized void add(long candidateEpoch, int delta) {
      if (!open || candidateEpoch != epoch) {
        return;
      }
      count = Math.max(0, count + delta);
    }

    synchronized void closeEpoch() {
      open = false;
      epoch++;
      count = 0;
    }
  }

  private record PanelPreview(PanelDefinition definition, PanelTransform effectiveTransform) {
    private PanelPreview {
      definition = Objects.requireNonNull(definition, "definition");
      effectiveTransform = Objects.requireNonNull(effectiveTransform, "effectiveTransform");
      if (definition.follow().mode() != PanelFollowMode.PLAYER
          && !definition.transform().worldUuid().equals(effectiveTransform.worldUuid())) {
        throw new IllegalArgumentException("preview definition and effective transform must share a world");
      }
    }
  }

  private record PlayerPresence(Player player, UUID worldUuid, double x, double z) {
  }
}
