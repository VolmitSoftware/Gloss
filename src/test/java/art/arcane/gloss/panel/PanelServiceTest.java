package art.arcane.gloss.panel;

import art.arcane.gloss.doc.DocumentRevisionConflictException;
import art.arcane.gloss.doc.ExecutorStorageTaskRunner;
import art.arcane.gloss.doc.StorageTaskRunner;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PanelServiceTest {
  private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000201");
  private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void mutationsAreSerializedAndPublishedBeforeListenersRun() throws IOException {
    File pluginData = temp.newFolder("publication");
    ManualTaskRunner runner = new ManualTaskRunner();
    PanelService service = service(new PanelRepository(pluginData), runner);
    RecordingListener listener = new RecordingListener(service);
    service.addListener(listener);

    CompletableFuture<PanelLoadResult> startup = service.start();
    assertTrue(service.list().isEmpty());
    assertEquals(1, runner.size());
    runner.runNext();
    assertTrue(startup.join().successful());
    assertEquals(1, listener.reloads.size());

    PanelDefinition createdInput = board("spawn/main", 0.0D, 0.0D)
        .withVisibility(PanelVisibility.publicAccess().withRanges(96.0D, 8.0D));
    CompletableFuture<PanelDefinition> createdFuture = service.create(createdInput);
    CompletableFuture<PanelDefinition> updatedFuture = service.update(createdInput.id(),
        PanelDefinition.INITIAL_REVISION, board -> board.withTransform(
            PanelTransform.at("example:world", WORLD_UUID, 32.0D, 70.0D, -4.0D, 90.0D)));

    assertEquals(1, runner.size());
    assertTrue(service.list().isEmpty());
    runner.runNext();
    PanelDefinition created = createdFuture.join();
    assertEquals(1, runner.size());
    assertEquals(created, service.get(created.id()).orElseThrow());
    assertEquals(96.0D, service.maximumViewRange(), 0.0D);
    assertEquals(List.of(created), listener.created);
    assertTrue(listener.allCallbacksObservedPublishedState);

    runner.runNext();
    PanelDefinition updated = updatedFuture.join();
    assertEquals(created.revision() + 1L, updated.revision());
    assertEquals(List.of(updated), service.query(WORLD_UUID, 32.0D, -4.0D, 0.0D));
    assertEquals(List.of(updated), listener.updated);
    assertTrue(listener.allCallbacksObservedPublishedState);

    CompletableFuture<PanelDefinition> renamedFuture = service.rename(updated.id(), "lobbies/info", updated.revision());
    runner.runNext();
    PanelDefinition renamed = renamedFuture.join();
    assertFalse(service.get(updated.id()).isPresent());
    assertEquals(renamed, service.get("LOBBIES/INFO").orElseThrow());
    assertEquals(created.uuid(), renamed.uuid());
    assertEquals(updated.revision() + 1L, renamed.revision());

    CompletableFuture<PanelDefinition> deletedFuture = service.delete(renamed.id(), renamed.revision());
    runner.runNext();
    assertEquals(renamed, deletedFuture.join());
    assertTrue(service.list().isEmpty());
    assertEquals(0.0D, service.maximumViewRange(), 0.0D);
    assertEquals(List.of(renamed), listener.deleted);
    assertTrue(listener.allCallbacksObservedPublishedState);
  }

  @Test
  public void externalBoardPublicationLoadsTheSavedRevisionAndNotifiesRuntimeListeners()
      throws IOException {
    File pluginData = temp.newFolder("external-publication");
    ManualTaskRunner runner = new ManualTaskRunner();
    PanelService service = service(new PanelRepository(pluginData), runner);
    RecordingListener listener = new RecordingListener(service);
    service.addListener(listener);
    service.start();
    runner.runNext();

    CompletableFuture<PanelDefinition> createdFuture = service.create(board("spawn/live", 0.0D, 0.0D));
    runner.runNext();
    PanelDefinition created = createdFuture.join();
    PanelDefinition edited = new PanelDefinition(
        created.schemaVersion(), created.id(), created.uuid(), created.revision() + 1L,
        created.rootMenuId(),
        PanelTransform.at("example:world", WORLD_UUID, 12.0D, 72.0D, -6.0D, 45.0D),
        created.follow(), created.visibility());
    Path boardFile = pluginData.toPath().resolve("panels/spawn/live.json");
    Files.writeString(boardFile, GSON.toJson(edited) + System.lineSeparator());

    PanelDefinition published = service.publishExternalUpdate(created, edited);

    assertEquals(edited, published);
    assertEquals(edited, service.get(edited.id()).orElseThrow());
    assertEquals(List.of(edited), listener.updated);
    assertTrue(listener.allCallbacksObservedPublishedState);
    PanelRepository reloaded = new PanelRepository(pluginData);
    assertTrue(reloaded.load().successful());
    assertEquals(edited, reloaded.get(edited.id()).orElseThrow());
  }

  @Test
  public void externalBoardCreationCanBePublishedAndRolledBackWithoutAResidualIndexEntry()
      throws IOException {
    File pluginData = temp.newFolder("external-create-publication");
    ManualTaskRunner runner = new ManualTaskRunner();
    PanelService service = service(new PanelRepository(pluginData), runner);
    RecordingListener listener = new RecordingListener(service);
    service.addListener(listener);
    service.start();
    runner.runNext();
    PanelDefinition created = board("spawn/welcome", 4.0D, 8.0D);
    Path boardFile = pluginData.toPath().resolve("panels/spawn/welcome.json");
    Files.createDirectories(boardFile.getParent());
    Files.writeString(boardFile, GSON.toJson(created) + System.lineSeparator());

    assertEquals(created, service.publishExternalCreate(created));
    assertEquals(created, service.get(created.id()).orElseThrow());
    assertEquals(List.of(created), listener.created);

    Files.delete(boardFile);
    assertEquals(created, service.recoverExternalCreate(created));
    assertTrue(service.get(created.id()).isEmpty());
    assertEquals(List.of(created), listener.deleted);
    assertTrue(listener.allCallbacksObservedPublishedState);
  }

  @Test
  public void repositoryFailureAndStaleRevisionNeverPublish() throws IOException {
    ManualTaskRunner failingRunner = new ManualTaskRunner();
    FailingCreateStore failingStore = new FailingCreateStore(temp.newFolder("failing").toPath());
    PanelService failingService = service(failingStore, failingRunner);
    RecordingListener failingListener = new RecordingListener(failingService);
    failingService.addListener(failingListener);
    failingService.start();
    failingRunner.runNext();

    CompletableFuture<PanelDefinition> failedCreate = failingService.create(board("failed", 0.0D, 0.0D));
    failingRunner.runNext();
    CompletionException diskFailure = assertThrows(CompletionException.class, failedCreate::join);
    assertTrue(diskFailure.getCause() instanceof IOException);
    assertTrue(failingService.list().isEmpty());
    assertTrue(failingListener.created.isEmpty());

    ManualTaskRunner revisionRunner = new ManualTaskRunner();
    PanelService revisionService = service(new PanelRepository(temp.newFolder("revision")), revisionRunner);
    RecordingListener revisionListener = new RecordingListener(revisionService);
    revisionService.addListener(revisionListener);
    revisionService.start();
    revisionRunner.runNext();
    CompletableFuture<PanelDefinition> createdFuture = revisionService.create(board("revision", 0.0D, 0.0D));
    revisionRunner.runNext();
    PanelDefinition created = createdFuture.join();

    CompletableFuture<PanelDefinition> staleUpdate = revisionService.update(created.id(),
        created.revision() + 1L, board -> board.withRootMenu("Other"));
    revisionRunner.runNext();
    CompletionException revisionFailure = assertThrows(CompletionException.class, staleUpdate::join);
    assertTrue(revisionFailure.getCause() instanceof DocumentRevisionConflictException);
    assertEquals(created, revisionService.get(created.id()).orElseThrow());
    assertTrue(revisionListener.updated.isEmpty());
  }

  @Test
  public void subscriptionReturnsThePublishedSnapshotBeforeLaterEvents() throws IOException {
    File pluginData = temp.newFolder("subscribe");
    PanelRepository seedRepository = new PanelRepository(pluginData);
    seedRepository.load();
    PanelDefinition seeded = seedRepository.create(board("existing", 0.0D, 0.0D));
    ManualTaskRunner runner = new ManualTaskRunner();
    PanelService service = service(new PanelRepository(pluginData), runner);
    service.start();
    runner.runNext();
    RecordingListener listener = new RecordingListener(service);

    List<PanelDefinition> snapshot = service.subscribeAndSnapshot(listener);
    CompletableFuture<PanelDefinition> createdFuture = service.create(board("later", 8.0D, 8.0D));
    runner.runNext();
    PanelDefinition created = createdFuture.join();

    assertEquals(List.of(seeded), snapshot);
    assertEquals(List.of(created), listener.created);
    assertTrue(listener.allCallbacksObservedPublishedState);
  }

  @Test
  public void subscriberAfterPublicationReceivesTheSnapshotWithoutADuplicateEvent() throws IOException {
    ManualTaskRunner runner = new ManualTaskRunner();
    AtomicBoolean subscribeAfterPublication = new AtomicBoolean();
    AtomicReference<PanelService> serviceReference = new AtomicReference<>();
    AtomicReference<List<PanelDefinition>> lateSnapshot = new AtomicReference<>();
    AtomicReference<RecordingListener> lateListener = new AtomicReference<>();
    Logger logger = Logger.getLogger(PanelServiceTest.class.getName() + ".linearized-subscription");
    logger.setLevel(Level.OFF);
    PanelService service = new PanelService(new PanelService.Dependencies(
        new PanelRepository(temp.newFolder("linearized-subscription")), runner, logger,
        new GlossPersistenceCoordinator(), () -> {
        }), () -> {
      if (subscribeAfterPublication.compareAndSet(true, false)) {
        lateSnapshot.set(serviceReference.get().subscribeAndSnapshot(lateListener.get()));
      }
    });
    serviceReference.set(service);
    RecordingListener early = new RecordingListener(service);
    RecordingListener late = new RecordingListener(service);
    lateListener.set(late);
    service.addListener(early);
    service.start();
    runner.runNext();

    subscribeAfterPublication.set(true);
    CompletableFuture<PanelDefinition> firstFuture = service.create(board("first", 0.0D, 0.0D));
    runner.runNext();
    PanelDefinition first = firstFuture.join();

    assertEquals(List.of(first), lateSnapshot.get());
    assertEquals(List.of(first), early.created);
    assertTrue(late.created.isEmpty());

    CompletableFuture<PanelDefinition> secondFuture = service.create(board("second", 8.0D, 8.0D));
    runner.runNext();
    PanelDefinition second = secondFuture.join();

    assertEquals(List.of(first, second), early.created);
    assertEquals(List.of(second), late.created);
  }

  @Test
  public void externalCreationWaitsUntilAnActiveReloadPublishesItsSnapshot() throws Exception {
    File pluginData = temp.newFolder("external-create-reload-race");
    ManualTaskRunner runner = new ManualTaskRunner();
    GlossPersistenceCoordinator coordinator = new GlossPersistenceCoordinator();
    AtomicInteger publications = new AtomicInteger();
    CountDownLatch reloadReadyToPublish = new CountDownLatch(1);
    CountDownLatch releaseReloadPublication = new CountDownLatch(1);
    Logger logger = Logger.getLogger(PanelServiceTest.class.getName() + ".external-create-race");
    logger.setLevel(Level.OFF);
    PanelService service = new PanelService(new PanelService.Dependencies(
        new PanelRepository(pluginData), runner, logger, coordinator, () -> {
          if (publications.incrementAndGet() != 2) {
            return;
          }
          reloadReadyToPublish.countDown();
          try {
            if (!releaseReloadPublication.await(5L, TimeUnit.SECONDS)) {
              throw new AssertionError("reload publication release timed out");
            }
          } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interruption);
          }
        }));
    service.start();
    runner.runNext();

    CompletableFuture<PanelLoadResult> reload = service.reload();
    Thread reloadThread = new Thread(runner::runNext);
    reloadThread.start();
    assertTrue(reloadReadyToPublish.await(5L, TimeUnit.SECONDS));

    PanelDefinition created = board("spawn/race", 3.0D, 5.0D);
    CountDownLatch externalLeaseAcquired = new CountDownLatch(1);
    AtomicReference<Throwable> externalFailure = new AtomicReference<>();
    Thread external = new Thread(() -> {
      try (GlossPersistenceCoordinator.ExternalTransaction ignored =
               coordinator.beginExternalTransaction()) {
        externalLeaseAcquired.countDown();
        Path boardFile = pluginData.toPath().resolve("panels/spawn/race.json");
        Files.createDirectories(boardFile.getParent());
        Files.writeString(boardFile, GSON.toJson(created) + System.lineSeparator());
        service.publishExternalCreate(created);
      } catch (Throwable failure) {
        externalFailure.set(failure);
      }
    });
    external.start();

    boolean acquiredBeforeReloadPublished =
        externalLeaseAcquired.await(100L, TimeUnit.MILLISECONDS);
    releaseReloadPublication.countDown();
    reloadThread.join(TimeUnit.SECONDS.toMillis(5L));
    external.join(TimeUnit.SECONDS.toMillis(5L));

    assertFalse(acquiredBeforeReloadPublished);
    assertFalse(reloadThread.isAlive());
    assertFalse(external.isAlive());
    assertNull(externalFailure.get());
    assertTrue(reload.join().successful());
    assertEquals(created, service.get(created.id()).orElseThrow());
  }

  @Test
  public void shutdownCancelsActiveAndQueuedOperationsAndRejectsNewWork() throws IOException {
    ManualTaskRunner runner = new ManualTaskRunner();
    PanelService service = service(new PanelRepository(temp.newFolder("shutdown")), runner);

    CompletableFuture<PanelLoadResult> startup = service.start();
    CompletableFuture<PanelDefinition> queuedCreate = service.create(board("queued", 0.0D, 0.0D));
    assertEquals(1, runner.size());

    service.shutdown();

    assertFalse(service.isRunning());
    assertThrows(CancellationException.class, startup::join);
    assertThrows(CancellationException.class, queuedCreate::join);
    runner.runAll();
    assertTrue(service.list().isEmpty());

    CompletableFuture<PanelLoadResult> rejectedReload = service.reload();
    CompletableFuture<PanelDefinition> rejectedCreate = service.create(board("rejected", 0.0D, 0.0D));
    assertThrows(CancellationException.class, rejectedReload::join);
    assertThrows(CancellationException.class, rejectedCreate::join);
    assertEquals(0, runner.size());
  }

  @Test
  public void shutdownDuringDispatchWaitsForTheActiveWriteWithoutInterruptingIt() throws Exception {
    BlockingCreateStore store = new BlockingCreateStore(temp.newFolder("active-shutdown"));
    DispatchRaceTaskRunner runner = new DispatchRaceTaskRunner();
    PanelService service = service(store, runner);
    assertTrue(service.start().get(5L, TimeUnit.SECONDS).successful());
    PanelDefinition input = board("active", 0.0D, 0.0D);
    AtomicReference<CompletableFuture<PanelDefinition>> writeReference = new AtomicReference<>();
    AtomicReference<Throwable> createFailure = new AtomicReference<>();
    Thread create = new Thread(() -> {
      try {
        writeReference.set(service.create(input));
      } catch (Throwable failure) {
        createFailure.set(failure);
      }
    }, "board-service-create-test");
    Thread shutdown = new Thread(service::shutdown, "board-service-shutdown-test");

    try {
      create.start();
      assertTrue(store.createStarted.await(5L, TimeUnit.SECONDS));
      shutdown.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
      while (service.isRunning() && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertFalse(service.isRunning());
      runner.allowSubmissionReturn.countDown();
      create.join(TimeUnit.SECONDS.toMillis(5L));

      assertFalse(create.isAlive());
      assertNull(createFailure.get());
      assertNotNull(writeReference.get());
      assertTrue(shutdown.isAlive());
      assertFalse(store.interrupted.get());
      assertFalse(runner.submitInterrupted.get());

      store.allowCreate.countDown();
      shutdown.join(TimeUnit.SECONDS.toMillis(5L));

      assertFalse(shutdown.isAlive());
      assertFalse(store.interrupted.get());
      assertThrows(CancellationException.class, writeReference.get()::join);
      assertEquals(input, store.get(input.id()).orElseThrow());
      assertTrue(service.list().isEmpty());
    } finally {
      runner.allowSubmissionReturn.countDown();
      store.allowCreate.countDown();
      create.join(TimeUnit.SECONDS.toMillis(5L));
      if (shutdown.isAlive()) {
        shutdown.join(TimeUnit.SECONDS.toMillis(5L));
      }
    }
  }

  private static PanelService service(PanelStore store, StorageTaskRunner taskRunner) {
    Logger logger = Logger.getLogger(PanelServiceTest.class.getName() + "." + UUID.randomUUID());
    logger.setLevel(Level.OFF);
    return new PanelService(new PanelService.Dependencies(
        store, taskRunner, logger, new GlossPersistenceCoordinator(), () -> {
        }));
  }

  private static PanelDefinition board(String id, double x, double z) {
    return PanelDefinition.create(id, "menu",
        PanelTransform.at("example:world", WORLD_UUID, x, 64.0D, z, 0.0D));
  }

  private static final class ManualTaskRunner implements StorageTaskRunner {
    private final ArrayDeque<ManualTask> tasks = new ArrayDeque<>();

    @Override
    public StorageTaskHandle submit(Runnable task) {
      ManualTask scheduled = new ManualTask(task);
      tasks.addLast(scheduled);
      return scheduled::cancel;
    }

    private int size() {
      return tasks.size();
    }

    private void runNext() {
      ManualTask task = tasks.removeFirst();
      task.run();
    }

    private void runAll() {
      while (!tasks.isEmpty()) {
        runNext();
      }
    }
  }

  private static final class DispatchRaceTaskRunner implements StorageTaskRunner {
    private final ExecutorStorageTaskRunner delegate = new ExecutorStorageTaskRunner(
        PanelServiceTest.class.getClassLoader(), "Gloss-Panel-Storage");
    private final AtomicInteger submissions = new AtomicInteger();
    private final CountDownLatch allowSubmissionReturn = new CountDownLatch(1);
    private final AtomicBoolean submitInterrupted = new AtomicBoolean();

    @Override
    public StorageTaskHandle submit(Runnable task) {
      StorageTaskHandle handle = delegate.submit(task);
      if (submissions.incrementAndGet() != 2) {
        return handle;
      }
      try {
        allowSubmissionReturn.await();
      } catch (InterruptedException interruption) {
        submitInterrupted.set(true);
        Thread.currentThread().interrupt();
      }
      return handle;
    }

    @Override
    public void shutdown() {
      delegate.shutdown();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return delegate.awaitTermination(timeout, unit);
    }
  }

  private static final class ManualTask {
    private final Runnable action;
    private boolean cancelled;

    private ManualTask(Runnable action) {
      this.action = action;
    }

    private void cancel() {
      cancelled = true;
    }

    private void run() {
      if (!cancelled) {
        action.run();
      }
    }
  }

  private static final class RecordingListener implements PanelServiceListener {
    private final PanelService service;
    private final List<PanelDefinition> created = new ArrayList<>();
    private final List<PanelDefinition> updated = new ArrayList<>();
    private final List<PanelDefinition> deleted = new ArrayList<>();
    private final List<PanelLoadResult> reloads = new ArrayList<>();
    private boolean allCallbacksObservedPublishedState = true;

    private RecordingListener(PanelService service) {
      this.service = service;
    }

    @Override
    public void boardCreated(PanelDefinition board) {
      created.add(board);
      allCallbacksObservedPublishedState &= service.get(board.id()).orElse(null) == board;
    }

    @Override
    public void boardUpdated(PanelDefinition previous, PanelDefinition updatedBoard) {
      updated.add(updatedBoard);
      allCallbacksObservedPublishedState &= service.get(updatedBoard.id()).orElse(null) == updatedBoard;
      allCallbacksObservedPublishedState &= previous.id().equals(updatedBoard.id())
          || service.get(previous.id()).isEmpty();
    }

    @Override
    public void boardDeleted(PanelDefinition board) {
      deleted.add(board);
      allCallbacksObservedPublishedState &= service.get(board.id()).isEmpty();
    }

    @Override
    public void boardsReloaded(PanelLoadResult result, List<PanelDefinition> boards) {
      reloads.add(result);
      allCallbacksObservedPublishedState &= service.list().equals(boards);
    }
  }

  private static final class FailingCreateStore implements PanelStore {
    private final Path directory;

    private FailingCreateStore(Path directory) {
      this.directory = directory;
    }

    @Override
    public Path directory() {
      return directory;
    }

    @Override
    public PanelLoadResult load() {
      return new PanelLoadResult(0, 0, 0, java.util.Map.of());
    }

    @Override
    public Optional<PanelDefinition> get(String id) {
      return Optional.empty();
    }

    @Override
    public List<PanelDefinition> list() {
      return List.of();
    }

    @Override
    public PanelDefinition create(PanelDefinition definition) throws IOException {
      throw new IOException("simulated storage failure");
    }

    @Override
    public PanelDefinition update(String id, long expectedRevision,
                                  UnaryOperator<PanelDefinition> update) {
      throw new NoSuchElementException(id);
    }

    @Override
    public PanelDefinition rename(String id, String newId, long expectedRevision) {
      throw new NoSuchElementException(id);
    }

    @Override
    public PanelDefinition delete(String id, long expectedRevision) {
      throw new NoSuchElementException(id);
    }

    @Override
    public PanelDefinition publishExternalCreate(PanelDefinition created) {
      throw new NoSuchElementException(created.id());
    }

    @Override
    public PanelDefinition recoverExternalCreate(PanelDefinition created) {
      throw new NoSuchElementException(created.id());
    }

    @Override
    public PanelDefinition publishExternal(PanelDefinition expected, PanelDefinition updated) {
      throw new NoSuchElementException(expected.id());
    }

    @Override
    public PanelDefinition recoverExternal(PanelDefinition applied, PanelDefinition restored) {
      throw new NoSuchElementException(applied.id());
    }
  }

  private static final class BlockingCreateStore implements PanelStore {
    private final PanelRepository delegate;
    private final CountDownLatch createStarted = new CountDownLatch(1);
    private final CountDownLatch allowCreate = new CountDownLatch(1);
    private final AtomicBoolean interrupted = new AtomicBoolean();

    private BlockingCreateStore(File pluginData) {
      this.delegate = new PanelRepository(pluginData);
    }

    @Override
    public Path directory() {
      return delegate.directory();
    }

    @Override
    public PanelLoadResult load() throws IOException {
      return delegate.load();
    }

    @Override
    public Optional<PanelDefinition> get(String id) {
      return delegate.get(id);
    }

    @Override
    public List<PanelDefinition> list() {
      return delegate.list();
    }

    @Override
    public PanelDefinition create(PanelDefinition definition) throws IOException {
      createStarted.countDown();
      try {
        allowCreate.await();
      } catch (InterruptedException interruption) {
        interrupted.set(true);
      }
      return delegate.create(definition);
    }

    @Override
    public PanelDefinition update(String id, long expectedRevision,
                                  UnaryOperator<PanelDefinition> update) throws IOException {
      return delegate.update(id, expectedRevision, update);
    }

    @Override
    public PanelDefinition rename(String id, String newId, long expectedRevision) throws IOException {
      return delegate.rename(id, newId, expectedRevision);
    }

    @Override
    public PanelDefinition delete(String id, long expectedRevision) throws IOException {
      return delegate.delete(id, expectedRevision);
    }

    @Override
    public PanelDefinition publishExternalCreate(PanelDefinition created) throws IOException {
      return delegate.publishExternalCreate(created);
    }

    @Override
    public PanelDefinition recoverExternalCreate(PanelDefinition created) throws IOException {
      return delegate.recoverExternalCreate(created);
    }

    @Override
    public PanelDefinition publishExternal(PanelDefinition expected, PanelDefinition updated)
        throws IOException {
      return delegate.publishExternal(expected, updated);
    }

    @Override
    public PanelDefinition recoverExternal(PanelDefinition applied, PanelDefinition restored)
        throws IOException {
      return delegate.recoverExternal(applied, restored);
    }
  }
}
