package art.arcane.gloss.config.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentRevisionConflictException;
import art.arcane.gloss.doc.ExecutorStorageTaskRunner;
import art.arcane.gloss.doc.StorageTaskRunner;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import com.google.gson.JsonObject;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MenuMutationService {
  private static final long SHUTDOWN_TIMEOUT_SECONDS = 30L;
  private static final long FORCE_SHUTDOWN_TIMEOUT_SECONDS = 5L;

  private final MenuDocumentRepository repository;
  private final StorageTaskRunner taskRunner;
  private final Logger logger;
  private final GlossPersistenceCoordinator persistenceCoordinator;
  private final Object lifecycleLock;
  private final ArrayDeque<PendingOperation> pendingOperations;

  private boolean running;
  private PendingOperation activeOperation;
  private StorageTaskRunner.StorageTaskHandle activeTask;

  public MenuMutationService(JavaPlugin plugin, File pluginDataDirectory) {
    this(dependencies(plugin, pluginDataDirectory));
  }

  MenuMutationService(Dependencies dependencies) {
    Dependencies required = Objects.requireNonNull(dependencies, "dependencies");
    this.repository = required.repository();
    this.taskRunner = required.taskRunner();
    this.logger = required.logger();
    this.persistenceCoordinator = required.persistenceCoordinator();
    this.lifecycleLock = new Object();
    this.pendingOperations = new ArrayDeque<>();
    this.running = true;
  }

  public CompletableFuture<MenuDocument> mutate(String menuId, String expectedRevision,
                                                UnaryOperator<JsonObject> mutation) {
    String id = MenuIds.require(menuId);
    String revision = Objects.requireNonNull(expectedRevision, "expectedRevision");
    UnaryOperator<JsonObject> requiredMutation = Objects.requireNonNull(mutation, "mutation");
    return enqueue("mutate menu '" + id + "'",
        () -> repository.mutate(id, revision, requiredMutation));
  }

  public CompletableFuture<MenuDocument> copy(String sourceMenuId, String expectedRevision,
                                              String targetMenuId) {
    String source = MenuIds.require(sourceMenuId);
    String target = MenuIds.require(targetMenuId);
    String revision = Objects.requireNonNull(expectedRevision, "expectedRevision");
    return enqueue("copy menu '" + source + "' to '" + target + "'",
        () -> repository.copy(source, revision, target));
  }

  public CompletableFuture<MenuDocument> create(String menuId, String source) {
    String id = MenuIds.require(menuId);
    if (source == null || source.isEmpty()) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("menu document must not be empty"));
    }
    return enqueue("create menu '" + id + "'", () -> repository.create(id, source));
  }

  public void shutdown() {
    List<PendingOperation> cancelled;
    PendingOperation cancelledActive;
    synchronized (lifecycleLock) {
      if (!running) {
        return;
      }
      running = false;
      cancelled = List.copyOf(pendingOperations);
      pendingOperations.clear();
      cancelledActive = activeOperation;
      activeOperation = null;
      activeTask = null;
    }
    if (cancelledActive != null) {
      cancel(cancelledActive.future(), "menu mutation service shut down during an active write");
    }
    for (PendingOperation operation : cancelled) {
      cancel(operation.future(), "menu mutation service shut down before a queued write started");
    }
    taskRunner.shutdown();
    boolean interrupted = false;
    try {
      if (!taskRunner.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        logger.warning("Timed out draining menu storage operations; cancelling remaining work.");
        taskRunner.shutdownNow();
        if (!taskRunner.awaitTermination(FORCE_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          logger.warning("Menu storage operations did not stop after forced cancellation.");
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

  public boolean isRunning() {
    synchronized (lifecycleLock) {
      return running;
    }
  }

  private CompletableFuture<MenuDocument> enqueue(String description, MenuWork work) {
    CompletableFuture<MenuDocument> future = new CompletableFuture<>();
    PendingOperation operation = new PendingOperation(description, work, future);
    PendingOperation next;
    synchronized (lifecycleLock) {
      if (!running) {
        cancel(future, "menu mutation service is shut down");
        return future;
      }
      pendingOperations.addLast(operation);
      next = reserveNext();
    }
    if (next != null) {
      dispatch(next);
    }
    return future;
  }

  private PendingOperation reserveNext() {
    if (!running || activeOperation != null || pendingOperations.isEmpty()) {
      return null;
    }
    activeOperation = pendingOperations.removeFirst();
    return activeOperation;
  }

  private void dispatch(PendingOperation operation) {
    StorageTaskRunner.StorageTaskHandle task;
    try {
      task = taskRunner.submit(() -> execute(operation));
    } catch (RuntimeException failure) {
      rejectDispatch(operation, failure);
      return;
    }
    if (task == null) {
      rejectDispatch(operation, new IllegalStateException("the asynchronous scheduler rejected the menu write"));
      return;
    }
    boolean cancelTask;
    synchronized (lifecycleLock) {
      cancelTask = !running || activeOperation != operation;
      if (!cancelTask) {
        activeTask = task;
      }
    }
    if (cancelTask) {
      task.cancel();
    }
  }

  private void execute(PendingOperation operation) {
    if (!isActive(operation)) {
      finish(operation);
      return;
    }
    try {
      MenuDocument document = persistenceCoordinator.write(operation.work()::execute);
      if (isActive(operation)) {
        operation.future().complete(document);
      } else {
        cancel(operation.future(), "menu mutation service shut down before the write completed");
      }
    } catch (Exception failure) {
      fail(operation, failure);
    } finally {
      finish(operation);
    }
  }

  private boolean isActive(PendingOperation operation) {
    synchronized (lifecycleLock) {
      return running && activeOperation == operation;
    }
  }

  private void rejectDispatch(PendingOperation operation, RuntimeException failure) {
    fail(operation, failure);
    finish(operation);
  }

  private void fail(PendingOperation operation, Exception failure) {
    if (operation.future().isDone()) {
      return;
    }
    if (!(failure instanceof DocumentRevisionConflictException)
        && !(failure instanceof FileAlreadyExistsException)
        && !(failure instanceof NoSuchFileException)
        && !(failure instanceof IllegalArgumentException)) {
      logger.log(Level.SEVERE, "Unable to " + operation.description() + ".", failure);
    }
    operation.future().completeExceptionally(failure);
  }

  private void finish(PendingOperation operation) {
    PendingOperation next;
    synchronized (lifecycleLock) {
      if (activeOperation != operation) {
        return;
      }
      activeOperation = null;
      activeTask = null;
      next = reserveNext();
    }
    if (next != null) {
      dispatch(next);
    }
  }

  private static void cancel(CompletableFuture<?> future, String reason) {
    future.completeExceptionally(new CancellationException(reason));
  }

  private static Dependencies dependencies(JavaPlugin plugin, File pluginDataDirectory) {
    JavaPlugin requiredPlugin = Objects.requireNonNull(plugin, "plugin");
    File requiredDirectory = Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory");
    StorageTaskRunner runner = new ExecutorStorageTaskRunner(requiredPlugin.getClass().getClassLoader(),
        "Gloss-Menu-Storage");
    GlossPersistenceCoordinator coordinator = requiredPlugin instanceof Gloss holoUi
        ? holoUi.getPersistenceCoordinator()
        : new GlossPersistenceCoordinator();
    return new Dependencies(new MenuDocumentRepository(requiredDirectory), runner,
        requiredPlugin.getLogger(), coordinator);
  }

  record Dependencies(MenuDocumentRepository repository, StorageTaskRunner taskRunner, Logger logger,
                      GlossPersistenceCoordinator persistenceCoordinator) {
    Dependencies {
      repository = Objects.requireNonNull(repository, "repository");
      taskRunner = Objects.requireNonNull(taskRunner, "taskRunner");
      logger = Objects.requireNonNull(logger, "logger");
      persistenceCoordinator = Objects.requireNonNull(persistenceCoordinator, "persistenceCoordinator");
    }
  }

  @FunctionalInterface
  interface MenuWork {
    MenuDocument execute() throws Exception;
  }

  private record PendingOperation(String description, MenuWork work,
                                  CompletableFuture<MenuDocument> future) {
    private PendingOperation {
      description = Objects.requireNonNull(description, "description");
      work = Objects.requireNonNull(work, "work");
      future = Objects.requireNonNull(future, "future");
    }
  }
}
