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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
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
import java.util.concurrent.atomic.AtomicInteger;

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
  private final PanelSpatialIndex effectiveIndex = new PanelSpatialIndex();
  private final Map<UUID, PanelDefinition> definitions = new ConcurrentHashMap<>();
  private final Map<UUID, ViewerState> viewers = new ConcurrentHashMap<>();
  private final Map<UUID, PanelPreview> previews = new ConcurrentHashMap<>();
  private final Set<UUID> tickingViewers = ConcurrentHashMap.newKeySet();
  private final Set<UUID> samplingTargets = ConcurrentHashMap.newKeySet();
  private final Map<UUID, PanelFollowPose> followPoses = new ConcurrentHashMap<>();
  private final AtomicInteger visibleBoards = new AtomicInteger();
  private final SchedulerUtils.TaskHandle tickTask;
  private final Events quitListener;

  private volatile boolean running = true;
  private volatile boolean hasPlayerFollowers;

  public PanelRuntimeManager(Gloss plugin, PanelService boards) {
    this.plugin = plugin;
    this.boards = boards;
    replaceDefinitions(boards.subscribeAndSnapshot(this));
    this.quitListener = Events.listen(plugin, PlayerQuitEvent.class, EventPriority.MONITOR,
        event -> closeViewer(event.getPlayer()));
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
    closeViewersForShutdown();
    viewers.clear();
    previews.clear();
    tickingViewers.clear();
    samplingTargets.clear();
    followPoses.clear();
    definitions.clear();
    hasPlayerFollowers = false;
    effectiveIndex.replaceAll(List.of());
    visibleBoards.set(0);
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
    definitions.remove(board.uuid());
    effectiveIndex.remove(board.uuid());
    if (board.follow().targetPlayerUuid() != null) {
      removeUnusedFollowPose(board.follow().targetPlayerUuid());
    }
    previews.entrySet().removeIf(entry -> entry.getValue().definition().uuid().equals(board.uuid()));
    refreshFollowerFlag();
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
      sampleFollowTargets();
    } catch (RuntimeException failure) {
      // Sampling is best-effort bookkeeping; a throw here must not take the viewer loop with it.
      Gloss.logExceptionStack(false, failure, "Failed to sample persistent panel follow targets.");
    }
    if (idle()) {
      return;
    }
    for (Player player : Bukkit.getOnlinePlayers()) {
      UUID playerId = player.getUniqueId();
      if (!tickingViewers.add(playerId)) {
        continue;
      }
      Runnable tick = () -> {
        try {
          if (!running || !player.isOnline()) {
            closeViewer(player);
            return;
          }
          viewers.computeIfAbsent(playerId, ignored -> new ViewerState(player)).tick();
        } catch (RuntimeException failure) {
          Gloss.logExceptionStack(false, failure, "Failed to update persistent panels for %s.", player.getName());
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
  }

  /**
   * True when no viewer can have anything to do this tick: nothing placed, nothing being previewed
   * in the editor, and no viewer state holding open views that would need closing. A server with no
   * panels at all therefore pays nothing per player per tick.
   */
  private boolean idle() {
    return effectiveIndex.isEmpty() && previews.isEmpty() && viewers.isEmpty();
  }

  private void sampleFollowTargets() {
    if (!hasPlayerFollowers) {
      return;
    }
    Map<UUID, List<PanelDefinition>> byTarget = new HashMap<>();
    for (PanelDefinition board : definitions.values()) {
      if (board.follow().mode() != PanelFollowMode.PLAYER) {
        continue;
      }
      byTarget.computeIfAbsent(board.follow().targetPlayerUuid(), ignored -> new ArrayList<>()).add(board);
    }

    for (Map.Entry<UUID, List<PanelDefinition>> entry : byTarget.entrySet()) {
      UUID targetId = entry.getKey();
      Player target = Bukkit.getPlayer(targetId);
      if (target == null || !target.isOnline()) {
        continue;
      }
      if (!samplingTargets.add(targetId)) {
        continue;
      }
      List<PanelDefinition> snapshot = List.copyOf(entry.getValue());
      Runnable sample = () -> {
        try {
          if (!running || !target.isOnline()) {
            return;
          }
          Location targetLocation = target.getLocation();
          PanelFollowPose targetPose = PanelFollowPose.from(targetLocation);
          followPoses.put(targetId, targetPose);
          for (PanelDefinition sampled : snapshot) {
            PanelDefinition current = definitions.get(sampled.uuid());
            if (current == null || current.revision() != sampled.revision()
                || current.follow().mode() != PanelFollowMode.PLAYER) {
              continue;
            }
            PanelTransform transform = PanelFollowTransform.resolve(current, targetPose);
            effectiveIndex.upsert(current.withTransform(transform));
          }
        } catch (RuntimeException failure) {
          Gloss.logExceptionStack(false, failure, "Failed to update panels following %s.", target.getName());
        } finally {
          samplingTargets.remove(targetId);
        }
      };
      Runnable retired = () -> samplingTargets.remove(targetId);
      if (!FoliaScheduler.runEntity(plugin, target, sample, 0L, retired)) {
        samplingTargets.remove(targetId);
      }
    }
  }

  private void publishDefinition(PanelDefinition board) {
    PanelDefinition previous = definitions.put(board.uuid(), board);
    if (previous != null && previous.follow().targetPlayerUuid() != null
        && !Objects.equals(previous.follow().targetPlayerUuid(), board.follow().targetPlayerUuid())) {
      removeUnusedFollowPose(previous.follow().targetPlayerUuid());
    }
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
    refreshFollowerFlag();
  }

  private void replaceDefinitions(List<PanelDefinition> loadedBoards) {
    definitions.clear();
    List<PanelDefinition> effectiveBoards = new ArrayList<>(loadedBoards.size());
    Set<UUID> activeFollowTargets = new HashSet<>();
    for (PanelDefinition board : loadedBoards) {
      definitions.put(board.uuid(), board);
      if (board.follow().mode() == PanelFollowMode.NONE) {
        effectiveBoards.add(board);
        continue;
      }
      activeFollowTargets.add(board.follow().targetPlayerUuid());
      PanelFollowPose pose = followPoses.get(board.follow().targetPlayerUuid());
      if (pose != null) {
        effectiveBoards.add(board.withTransform(PanelFollowTransform.resolve(board, pose)));
      }
    }
    effectiveIndex.replaceAll(effectiveBoards);
    followPoses.keySet().removeIf(targetId -> !activeFollowTargets.contains(targetId));
    refreshFollowerFlag();
  }

  /** Recomputed from the definition table, which is small and only changes on an operator edit. */
  private void refreshFollowerFlag() {
    for (PanelDefinition board : definitions.values()) {
      if (board.follow().mode() == PanelFollowMode.PLAYER) {
        hasPlayerFollowers = true;
        return;
      }
    }
    hasPlayerFollowers = false;
  }

  private void removeUnusedFollowPose(UUID targetId) {
    boolean used = definitions.values().stream()
        .anyMatch(board -> targetId.equals(board.follow().targetPlayerUuid()));
    if (!used) {
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
    boolean interrupted = false;
    while (closed.getCount() > 0L) {
      try {
        if (!closed.await(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          Gloss.log(java.util.logging.Level.WARNING,
              "Still waiting for persistent panel viewer tasks to close during shutdown.");
        }
      } catch (InterruptedException interruption) {
        interrupted = true;
      }
    }
    if (interrupted) {
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
    }

    private synchronized void tick() {
      if (closed || !running) {
        close();
        return;
      }
      Location location = player.getLocation();
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
              plugin.getConfigManager(),
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
          visibleBoards.incrementAndGet();
        } else {
          NavigationResult updateResult = view.update(definition, effective.transform());
          if (updateResult != NavigationResult.APPLIED) {
            views.remove(definition.uuid(), view);
            anyViews = !views.isEmpty();
            visibleBoards.decrementAndGet();
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
          visibleBoards.decrementAndGet();
        }
      }
      anyViews = !views.isEmpty();
      dismissed.removeIf(boardId -> !inRange.contains(boardId));
      unavailable.keySet().removeIf(boardId -> !inRange.contains(boardId));
    }

    /**
     * The panels this viewer could possibly see, reused for as long as it stays in the chunk it
     * queried from and the index has not changed. The query is a candidate prefilter — the caller
     * still range-tests every entry against that panel's own view range — so a padded, slightly
     * over-broad list produces exactly the same membership.
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
      if (generation == cachedGeneration && queryRange == cachedRange && chunkX == cachedChunkX
          && chunkZ == cachedChunkZ && world.getUID().equals(cachedWorld)) {
        return cachedCandidates;
      }
      cachedCandidates = effectiveIndex.query(world.getUID(), location.getX(), location.getZ(),
          queryRange + CHUNK_QUERY_PADDING);
      cachedWorld = world.getUID();
      cachedGeneration = generation;
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
          boolean refreshed = plugin.getConfigManager().exists(view.currentMenuId())
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
        visibleBoards.addAndGet(-count);
      }
    }

    private void closeView(UUID boardId) {
      PanelViewSession view = views.remove(boardId);
      if (view != null) {
        anyViews = !views.isEmpty();
        view.close();
        visibleBoards.decrementAndGet();
      }
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
}
