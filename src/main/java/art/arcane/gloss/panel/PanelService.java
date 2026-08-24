package art.arcane.gloss.panel;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentRevisionConflictException;
import art.arcane.gloss.doc.ExecutorStorageTaskRunner;
import art.arcane.gloss.doc.StorageTaskRunner;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PanelService {
  private static final long SHUTDOWN_TIMEOUT_SECONDS = 30L;
  private static final long FORCE_SHUTDOWN_TIMEOUT_SECONDS = 5L;
  private final PanelStore store;
  private final StorageTaskRunner taskRunner;
  private final Logger logger;
  private final PanelSpatialIndex spatialIndex;
  private final CopyOnWriteArrayList<PanelServiceListener> listeners;
  private final Object lifecycleLock;
  private final ArrayDeque<PendingOperation<?>> pendingOperations;
  private final CompletableFuture<PanelLoadResult> startupFuture;
  private final Runnable beforeIndexPublication;
  private final Runnable afterPublication;
  private final GlossPersistenceCoordinator persistenceCoordinator;

  private Lifecycle lifecycle;
  private PendingOperation<?> activeOperation;
  private StorageTaskRunner.StorageTaskHandle activeTask;
  private volatile double maximumViewRange;

  public PanelService(JavaPlugin plugin) {
    this(dependencies(plugin));
  }

  PanelService(Dependencies dependencies) {
    this(dependencies, () -> {
    });
  }

  PanelService(Dependencies dependencies, Runnable afterPublication) {
    Dependencies requiredDependencies = Objects.requireNonNull(dependencies, "dependencies");
    this.store = requiredDependencies.store();
    this.taskRunner = requiredDependencies.taskRunner();
    this.logger = requiredDependencies.logger();
    this.beforeIndexPublication = requiredDependencies.beforeIndexPublication();
    this.afterPublication = Objects.requireNonNull(afterPublication, "afterPublication");
    this.persistenceCoordinator = requiredDependencies.persistenceCoordinator();
    this.spatialIndex = new PanelSpatialIndex();
    this.listeners = new CopyOnWriteArrayList<>();
    this.lifecycleLock = new Object();
    this.pendingOperations = new ArrayDeque<>();
    this.startupFuture = new CompletableFuture<>();
    this.lifecycle = Lifecycle.NEW;
  }

  public CompletableFuture<PanelLoadResult> start() {
    synchronized (lifecycleLock) {
      if (lifecycle == Lifecycle.RUNNING) {
        return startupFuture;
      }
      if (lifecycle == Lifecycle.STOPPED) {
        cancel(startupFuture, "panel service is shut down");
        return startupFuture;
      }
      lifecycle = Lifecycle.RUNNING;
    }

    enqueue(new PendingOperation<>("load persistent panels during startup", this::loadBoards, startupFuture));
    return startupFuture;
  }

  public CompletableFuture<PanelLoadResult> reload() {
    return enqueue("reload persistent panels", this::loadBoards);
  }

  public CompletableFuture<PanelDefinition> create(PanelDefinition definition) {
    PanelDefinition requiredDefinition = Objects.requireNonNull(definition, "definition");
    return enqueue("create panel '" + requiredDefinition.id() + "'", () -> {
      PanelDefinition created = store.create(requiredDefinition);
      return new WorkResult<>(created, () -> publishCreatedIndex(created),
          notificationListeners -> notifyCreated(created, notificationListeners));
    });
  }

  public CompletableFuture<PanelDefinition> update(String id, long expectedRevision,
                                                   UnaryOperator<PanelDefinition> update) {
    String canonicalId = PanelIds.canonicalize(id);
    UnaryOperator<PanelDefinition> requiredUpdate = Objects.requireNonNull(update, "update");
    return enqueue("update panel '" + canonicalId + "'", () -> {
      PanelDefinition previous = store.get(canonicalId)
          .orElseThrow(() -> new NoSuchElementException("unknown panel: " + canonicalId));
      PanelDefinition updated = store.update(canonicalId, expectedRevision, requiredUpdate);
      PanelUpdate publication = new PanelUpdate(previous, updated);
      return new WorkResult<>(updated, () -> publishUpdatedIndex(publication),
          notificationListeners -> notifyUpdated(publication, notificationListeners));
    });
  }

  public CompletableFuture<PanelDefinition> delete(String id, long expectedRevision) {
    String canonicalId = PanelIds.canonicalize(id);
    return enqueue("delete panel '" + canonicalId + "'", () -> {
      PanelDefinition deleted = store.delete(canonicalId, expectedRevision);
      return new WorkResult<>(deleted, () -> publishDeletedIndex(deleted),
          notificationListeners -> notifyDeleted(deleted, notificationListeners));
    });
  }

  public CompletableFuture<PanelDefinition> rename(String id, String newId, long expectedRevision) {
    String canonicalId = PanelIds.canonicalize(id);
    String canonicalNewId = PanelIds.canonicalize(newId);
    return enqueue("rename panel '" + canonicalId + "' to '" + canonicalNewId + "'", () -> {
      PanelDefinition previous = store.get(canonicalId)
          .orElseThrow(() -> new NoSuchElementException("unknown panel: " + canonicalId));
      PanelDefinition renamed = store.rename(canonicalId, canonicalNewId, expectedRevision);
      PanelUpdate publication = new PanelUpdate(previous, renamed);
      return new WorkResult<>(renamed, () -> publishUpdatedIndex(publication),
          notificationListeners -> notifyUpdated(publication, notificationListeners));
    });
  }

  public PanelDefinition publishExternalCreate(PanelDefinition created) throws IOException {
    PanelDefinition requiredCreated = Objects.requireNonNull(created, "created");
    PanelDefinition published;
    List<PanelServiceListener> notificationListeners;
    synchronized (lifecycleLock) {
      if (lifecycle != Lifecycle.RUNNING) {
        throw new CancellationException("panel service is not running");
      }
      published = store.publishExternalCreate(requiredCreated);
      publishCreatedIndex(published);
      notificationListeners = List.copyOf(listeners);
    }
    afterPublication.run();
    notifyCreated(published, notificationListeners);
    return published;
  }

  public PanelDefinition recoverExternalCreate(PanelDefinition created) throws IOException {
    PanelDefinition requiredCreated = Objects.requireNonNull(created, "created");
    PanelDefinition recovered;
    List<PanelServiceListener> notificationListeners;
    synchronized (lifecycleLock) {
      if (lifecycle != Lifecycle.RUNNING) {
        throw new CancellationException("panel service is not running");
      }
      recovered = store.recoverExternalCreate(requiredCreated);
      if (spatialIndex.get(recovered.id()).isEmpty()) {
        return recovered;
      }
      publishDeletedIndex(recovered);
      notificationListeners = List.copyOf(listeners);
    }
    afterPublication.run();
    notifyDeleted(recovered, notificationListeners);
    return recovered;
  }

  public PanelDefinition publishExternalUpdate(PanelDefinition expected, PanelDefinition updated)
      throws IOException {
    PanelDefinition previous = Objects.requireNonNull(expected, "expected");
    PanelDefinition published;
    List<PanelServiceListener> notificationListeners;
    synchronized (lifecycleLock) {
      if (lifecycle != Lifecycle.RUNNING) {
        throw new CancellationException("panel service is not running");
      }
      published = store.publishExternal(previous, Objects.requireNonNull(updated, "updated"));
      publishUpdatedIndex(new PanelUpdate(previous, published));
      notificationListeners = List.copyOf(listeners);
    }
    afterPublication.run();
    notifyUpdated(new PanelUpdate(previous, published), notificationListeners);
    return published;
  }

  public PanelDefinition recoverExternalUpdate(PanelDefinition applied, PanelDefinition restored)
      throws IOException {
    PanelDefinition requiredApplied = Objects.requireNonNull(applied, "applied");
    PanelDefinition requiredRestored = Objects.requireNonNull(restored, "restored");
    PanelDefinition published;
    List<PanelServiceListener> notificationListeners;
    synchronized (lifecycleLock) {
      if (lifecycle != Lifecycle.RUNNING) {
        throw new CancellationException("panel service is not running");
      }
      published = store.recoverExternal(requiredApplied, requiredRestored);
      if (published.equals(spatialIndex.get(published.id()).orElse(null))) {
        return published;
      }
      publishUpdatedIndex(new PanelUpdate(requiredApplied, published));
      notificationListeners = List.copyOf(listeners);
    }
    afterPublication.run();
    notifyUpdated(new PanelUpdate(requiredApplied, published), notificationListeners);
    return published;
  }

  public Optional<PanelDefinition> get(String id) {
    return spatialIndex.get(id);
  }

  public Optional<PanelDefinition> get(UUID boardUuid) {
    return spatialIndex.get(boardUuid);
  }

  public List<PanelDefinition> list() {
    return spatialIndex.list();
  }

  public List<PanelDefinition> query(UUID worldUuid, double x, double z, double radius) {
    return spatialIndex.query(worldUuid, x, z, radius);
  }

  public int size() {
    return spatialIndex.size();
  }

  public double maximumViewRange() {
    return maximumViewRange;
  }

  public boolean isRunning() {
    synchronized (lifecycleLock) {
      return lifecycle == Lifecycle.RUNNING;
    }
  }

  public void addListener(PanelServiceListener listener) {
    listeners.addIfAbsent(Objects.requireNonNull(listener, "listener"));
  }

  public List<PanelDefinition> subscribeAndSnapshot(PanelServiceListener listener) {
    PanelServiceListener requiredListener = Objects.requireNonNull(listener, "listener");
    synchronized (lifecycleLock) {
      listeners.addIfAbsent(requiredListener);
      return spatialIndex.list();
    }
  }

  public void removeListener(PanelServiceListener listener) {
    listeners.remove(Objects.requireNonNull(listener, "listener"));
  }

  public void shutdown() {
    List<PendingOperation<?>> cancelledOperations;
    PendingOperation<?> cancelledActive;
    synchronized (lifecycleLock) {
      if (lifecycle == Lifecycle.STOPPED) {
        return;
      }
      lifecycle = Lifecycle.STOPPED;
      cancelledOperations = List.copyOf(pendingOperations);
      pendingOperations.clear();
      cancelledActive = activeOperation;
      activeOperation = null;
      activeTask = null;
    }

    listeners.clear();
    cancel(startupFuture, "panel service shut down before startup completed");
    if (cancelledActive != null) {
      cancel(cancelledActive.future(), "panel service shut down while the operation was active");
    }
    for (PendingOperation<?> operation : cancelledOperations) {
      cancel(operation.future(), "panel service shut down before the operation started");
    }
    taskRunner.shutdown();
    boolean interrupted = false;
    try {
      if (!taskRunner.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        logger.warning("Timed out draining panel storage operations; cancelling remaining work.");
        taskRunner.shutdownNow();
        if (!taskRunner.awaitTermination(FORCE_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          logger.warning("Panel storage operations did not stop after forced cancellation.");
        }
      }
    } catch (InterruptedException interruption) {
      interrupted = true;
      taskRunner.shutdownNow();
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private WorkResult<PanelLoadResult> loadBoards() throws IOException {
    PanelLoadResult result = store.load();
    List<PanelDefinition> loadedBoards = store.list();
    PanelReload publication = new PanelReload(result, loadedBoards);
    return new WorkResult<>(result, () -> publishReloadedIndex(publication),
        notificationListeners -> notifyReloaded(publication, notificationListeners));
  }

  private void publishCreatedIndex(PanelDefinition board) {
    spatialIndex.upsert(board);
    refreshMaximumViewRange();
  }

  private void notifyCreated(PanelDefinition board, List<PanelServiceListener> notificationListeners) {
    for (PanelServiceListener listener : notificationListeners) {
      notifyListener("created panel '" + board.id() + "'", () -> listener.boardCreated(board));
    }
  }

  private void publishUpdatedIndex(PanelUpdate publication) {
    spatialIndex.upsert(publication.updated());
    refreshMaximumViewRange();
  }

  private void notifyUpdated(PanelUpdate publication, List<PanelServiceListener> notificationListeners) {
    for (PanelServiceListener listener : notificationListeners) {
      notifyListener("updated panel '" + publication.updated().id() + "'",
          () -> listener.boardUpdated(publication.previous(), publication.updated()));
    }
  }

  private void publishDeletedIndex(PanelDefinition board) {
    spatialIndex.remove(board.uuid());
    refreshMaximumViewRange();
  }

  private void notifyDeleted(PanelDefinition board, List<PanelServiceListener> notificationListeners) {
    for (PanelServiceListener listener : notificationListeners) {
      notifyListener("deleted panel '" + board.id() + "'", () -> listener.boardDeleted(board));
    }
  }

  private void publishReloadedIndex(PanelReload publication) {
    spatialIndex.replaceAll(publication.boards());
    refreshMaximumViewRange();
  }

  private void notifyReloaded(PanelReload publication, List<PanelServiceListener> notificationListeners) {
    if (!isRunning()) {
      return;
    }
    logReload(publication.result(), publication.boards().size());
    for (PanelServiceListener listener : notificationListeners) {
      notifyListener("reloaded persistent panels",
          () -> listener.boardsReloaded(publication.result(), publication.boards()));
    }
  }

  private void refreshMaximumViewRange() {
    double highestRange = 0.0D;
    for (PanelDefinition board : spatialIndex.list()) {
      highestRange = Math.max(highestRange, board.visibility().viewRange());
    }
    maximumViewRange = highestRange;
  }

  private void logReload(PanelLoadResult result, int publishedCount) {
    if (result.failures().isEmpty()) {
      logger.info("Published " + publishedCount + " persistent panel(s) from " + store.directory() + ".");
      return;
    }

    logger.warning("Published " + publishedCount + " persistent panel(s) from " + store.directory()
        + "; " + result.failures().size() + " panel file(s) failed validation. "
        + result.retained() + " last-good definition(s) were retained.");
    for (Map.Entry<String, String> failure : result.failures().entrySet()) {
      logger.warning("Panel file '" + failure.getKey() + "' was not loaded: " + failure.getValue());
    }
  }

  private void notifyListener(String operation, Runnable notification) {
    if (!isRunning()) {
      return;
    }
    try {
      notification.run();
    } catch (RuntimeException failure) {
      logger.log(Level.SEVERE, "A panel listener failed after Gloss " + operation + ".", failure);
    }
  }

  private <T> CompletableFuture<T> enqueue(String description, PanelWork<T> work) {
    CompletableFuture<T> future = new CompletableFuture<>();
    enqueue(new PendingOperation<>(description, work, future));
    return future;
  }

  private void enqueue(PendingOperation<?> operation) {
    PendingOperation<?> nextOperation;
    synchronized (lifecycleLock) {
      if (lifecycle != Lifecycle.RUNNING) {
        cancel(operation.future(), "panel service is not running");
        return;
      }
      pendingOperations.addLast(operation);
      nextOperation = reserveNextOperation();
    }
    if (nextOperation != null) {
      dispatch(nextOperation);
    }
  }

  private PendingOperation<?> reserveNextOperation() {
    if (activeOperation != null || pendingOperations.isEmpty() || lifecycle != Lifecycle.RUNNING) {
      return null;
    }
    activeOperation = pendingOperations.removeFirst();
    return activeOperation;
  }

  private void dispatch(PendingOperation<?> operation) {
    StorageTaskRunner.StorageTaskHandle task;
    try {
      task = taskRunner.submit(() -> execute(operation));
    } catch (RuntimeException failure) {
      rejectDispatch(operation, failure);
      return;
    }
    if (task == null) {
      rejectDispatch(operation, new IllegalStateException("the asynchronous scheduler rejected the operation"));
      return;
    }

    boolean cancelTask;
    synchronized (lifecycleLock) {
      cancelTask = lifecycle != Lifecycle.RUNNING || activeOperation != operation;
      if (!cancelTask) {
        activeTask = task;
      }
    }
    if (cancelTask) {
      task.cancel();
    }
  }

  private void execute(PendingOperation<?> operation) {
    if (!isActive(operation)) {
      finish(operation);
      return;
    }

    try {
      WorkResult<?> result = persistenceCoordinator.write(() -> persistAndPublish(operation));
      complete(operation, result.value());
    } catch (Exception failure) {
      fail(operation, failure);
    } finally {
      finish(operation);
    }
  }

  private WorkResult<?> persistAndPublish(PendingOperation<?> operation) throws Exception {
    WorkResult<?> result = operation.work().execute();
    beforeIndexPublication.run();
    List<PanelServiceListener> notificationListeners =
        publish(operation, result.indexPublication());
    if (notificationListeners == null) {
      throw new CancellationException(
          "panel service shut down before the operation could be published");
    }
    afterPublication.run();
    result.notification().accept(notificationListeners);
    return result;
  }

  private List<PanelServiceListener> publish(PendingOperation<?> operation, Runnable publication) {
    synchronized (lifecycleLock) {
      if (lifecycle != Lifecycle.RUNNING || activeOperation != operation) {
        return null;
      }
      publication.run();
      return List.copyOf(listeners);
    }
  }

  private boolean isActive(PendingOperation<?> operation) {
    synchronized (lifecycleLock) {
      return lifecycle == Lifecycle.RUNNING && activeOperation == operation;
    }
  }

  private void rejectDispatch(PendingOperation<?> operation, RuntimeException failure) {
    if (!operation.future().isDone()) {
      logFailure(operation.description(), failure);
      operation.future().completeExceptionally(failure);
    }
    finish(operation);
  }

  private void fail(PendingOperation<?> operation, Exception failure) {
    if (operation.future().isDone()) {
      return;
    }
    logFailure(operation.description(), failure);
    operation.future().completeExceptionally(failure);
  }

  private void logFailure(String operation, Exception failure) {
    if (failure instanceof DocumentRevisionConflictException
        || failure instanceof NoSuchElementException
        || failure instanceof FileAlreadyExistsException
        || failure instanceof IllegalArgumentException) {
      return;
    }
    logger.log(Level.SEVERE, "Unable to " + operation + " in " + store.directory() + ".", failure);
  }

  private void finish(PendingOperation<?> operation) {
    PendingOperation<?> nextOperation;
    synchronized (lifecycleLock) {
      if (activeOperation != operation) {
        return;
      }
      activeOperation = null;
      activeTask = null;
      nextOperation = reserveNextOperation();
    }
    if (nextOperation != null) {
      dispatch(nextOperation);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> void complete(PendingOperation<?> operation, Object result) {
    PendingOperation<T> typedOperation = (PendingOperation<T>) operation;
    typedOperation.future().complete((T) result);
  }

  private static void cancel(CompletableFuture<?> future, String reason) {
    future.completeExceptionally(new CancellationException(reason));
  }

  private static Dependencies dependencies(JavaPlugin plugin) {
    JavaPlugin requiredPlugin = Objects.requireNonNull(plugin, "plugin");
    StorageTaskRunner taskRunner = new ExecutorStorageTaskRunner(requiredPlugin.getClass().getClassLoader(),
        "Gloss-Panel-Storage");
    GlossPersistenceCoordinator coordinator = requiredPlugin instanceof Gloss holoUi
        ? holoUi.getPersistenceCoordinator()
        : new GlossPersistenceCoordinator();
    return new Dependencies(new PanelRepository(requiredPlugin.getDataFolder()), taskRunner,
        requiredPlugin.getLogger(), coordinator, () -> {
        });
  }

  record Dependencies(PanelStore store, StorageTaskRunner taskRunner, Logger logger,
                      GlossPersistenceCoordinator persistenceCoordinator,
                      Runnable beforeIndexPublication) {
    Dependencies {
      store = Objects.requireNonNull(store, "store");
      taskRunner = Objects.requireNonNull(taskRunner, "taskRunner");
      logger = Objects.requireNonNull(logger, "logger");
      persistenceCoordinator = Objects.requireNonNull(persistenceCoordinator, "persistenceCoordinator");
      beforeIndexPublication = Objects.requireNonNull(
          beforeIndexPublication, "beforeIndexPublication");
    }
  }

  @FunctionalInterface
  private interface PanelWork<T> {
    WorkResult<T> execute() throws Exception;
  }

  private record WorkResult<T>(T value, Runnable indexPublication,
                               Consumer<List<PanelServiceListener>> notification) {
    private WorkResult {
      value = Objects.requireNonNull(value, "value");
      indexPublication = Objects.requireNonNull(indexPublication, "indexPublication");
      notification = Objects.requireNonNull(notification, "notification");
    }
  }

  private record PendingOperation<T>(String description, PanelWork<T> work, CompletableFuture<T> future) {
    private PendingOperation {
      description = Objects.requireNonNull(description, "description");
      work = Objects.requireNonNull(work, "work");
      future = Objects.requireNonNull(future, "future");
    }
  }

  private record PanelUpdate(PanelDefinition previous, PanelDefinition updated) {
  }

  private record PanelReload(PanelLoadResult result, List<PanelDefinition> boards) {
    private PanelReload {
      boards = List.copyOf(boards);
    }
  }

  private enum Lifecycle {
    NEW,
    RUNNING,
    STOPPED
  }
}
