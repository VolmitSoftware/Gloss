package art.arcane.gloss.panel;

import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.StorageTaskRunner;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PanelHotloadServiceTest {
  private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000251");

  @Test
  public void repeatedRequestsShareOneReadAndEmptyPollsDoNotPublish() {
    PanelDefinition existing = panel("existing", 0.0D);
    try (Fixture fixture = new Fixture(List.of(existing))) {
      CompletableFuture<DocumentDelta> first = fixture.service.poll();
      CompletableFuture<DocumentDelta> repeated = fixture.service.poll();

      assertSame(first, repeated);
      assertEquals(1, fixture.runner.size());
      assertEquals(0, fixture.store.polls.get());
      fixture.runner.runNext();

      assertTrue(first.join().isEmpty());
      assertEquals(1, fixture.store.polls.get());
      assertEquals(1, fixture.store.loads);
      assertEquals(List.of(existing), fixture.service.list());
      assertEquals(0, fixture.beforePublications.get());
      assertEquals(0, fixture.publications.get());
      assertTrue(fixture.listener.observations.isEmpty());
      assertEquals(0, fixture.listener.reloads);

      CompletableFuture<DocumentDelta> later = fixture.service.poll();
      assertNotSame(first, later);
      fixture.runner.runNext();
      assertTrue(later.join().isEmpty());
      assertEquals(2, fixture.store.polls.get());
      assertEquals(0, fixture.publications.get());
    }
  }

  @Test
  public void changedPanelsPublishTheirWholeBatchBeforeOnlyAffectedListenersRun() {
    PanelDefinition previous = panel("moved", 0.0D)
        .withVisibility(PanelVisibility.publicAccess().withRanges(128.0D, 8.0D));
    PanelDefinition removed = panel("removed", 128.0D)
        .withVisibility(PanelVisibility.publicAccess().withRanges(256.0D, 8.0D));
    PanelDefinition unchanged = panel("unchanged", 256.0D)
        .withVisibility(PanelVisibility.publicAccess().withRanges(32.0D, 8.0D));
    PanelDefinition updated = previous.withTransform(transform(64.0D))
        .withVisibility(PanelVisibility.publicAccess().withRanges(96.0D, 8.0D))
        .withRevision(previous.revision() + 1L);
    PanelDefinition added = panel("added", 512.0D);

    try (Fixture fixture = new Fixture(List.of(previous, removed, unchanged))) {
      fixture.store.stage(List.of(updated, added), List.of(removed.id()));
      CompletableFuture<DocumentDelta> result = fixture.service.poll();

      assertEquals(previous, fixture.service.get(previous.id()).orElseThrow());
      assertEquals(256.0D, fixture.service.maximumViewRange(), 0.0D);
      fixture.runner.runNext();

      assertEquals(new DocumentDelta(List.of(updated.id(), added.id()), List.of(removed.id())),
          result.join());
      assertEquals(3, fixture.listener.observations.size());
      assertEquals(Set.of(updated, added, unchanged), Set.copyOf(fixture.service.list()));
      assertEquals(96.0D, fixture.service.maximumViewRange(), 0.0D);
      assertEquals(1, fixture.beforePublications.get());
      assertEquals(1, fixture.publications.get());
      assertEquals(0, fixture.listener.reloads);

      Observation creation = fixture.listener.find("created", added.id());
      Observation update = fixture.listener.find("updated", updated.id());
      Observation deletion = fixture.listener.find("deleted", removed.id());
      assertNull(creation.previous());
      assertEquals(added, creation.current());
      assertEquals(previous, update.previous());
      assertEquals(updated, update.current());
      assertEquals(removed, deletion.previous());
      assertNull(deletion.current());
      for (Observation observation : fixture.listener.observations) {
        assertEquals(Set.of(updated, added, unchanged), observation.published());
        assertEquals(96.0D, observation.maximumViewRange(), 0.0D);
        assertEquals(observation.current(), observation.byUuid());
        assertEquals(observation.current() == null ? List.of() : List.of(observation.current()),
            observation.nearby());
      }
      assertEquals(List.of(), fixture.service.query(WORLD_UUID, 0.0D, 0.0D, 0.0D));
      assertSame(unchanged, fixture.service.get(unchanged.uuid()).orElseThrow());

      fixture.service.poll();
      fixture.runner.runNext();
      assertEquals(3, fixture.listener.observations.size());
      assertEquals(1, fixture.publications.get());
    }
  }

  @Test
  public void failedPollsKeepPublishedPanelsAndAllowTheNextPollToSucceed() {
    PanelDefinition previous = panel("existing", 0.0D);
    PanelDefinition updated = previous.withTransform(transform(64.0D))
        .withRevision(previous.revision() + 1L);
    IOException unreadable = new IOException("panel directory is temporarily unreadable");

    try (Fixture fixture = new Fixture(List.of(previous))) {
      fixture.store.nextFailure = unreadable;
      CompletableFuture<DocumentDelta> failed = fixture.service.poll();
      fixture.runner.runNext();

      CompletionException failure = assertThrows(CompletionException.class, failed::join);
      assertSame(unreadable, failure.getCause());
      assertEquals(previous, fixture.service.get(previous.id()).orElseThrow());
      assertTrue(fixture.listener.observations.isEmpty());
      assertEquals(0, fixture.beforePublications.get());
      assertEquals(0, fixture.publications.get());

      fixture.store.stage(List.of(updated), List.of());
      CompletableFuture<DocumentDelta> retried = fixture.service.poll();
      fixture.runner.runNext();

      assertEquals(List.of(updated.id()), retried.join().loaded());
      assertEquals(updated, fixture.service.get(updated.id()).orElseThrow());
      assertEquals(1, fixture.listener.observations.size());
      assertEquals(1, fixture.publications.get());
    }
  }

  @Test
  public void shutdownCancelsPendingPollsAndClosesTheirStoreWithoutReading() {
    PanelDefinition existing = panel("existing", 0.0D);
    try (Fixture fixture = new Fixture(List.of(existing))) {
      fixture.store.stage(List.of(panel("later", 64.0D)), List.of(existing.id()));
      CompletableFuture<DocumentDelta> pending = fixture.service.poll();

      fixture.service.shutdown();

      assertThrows(CancellationException.class, pending::join);
      assertTrue(fixture.runner.shutdown);
      assertEquals(1, fixture.store.closes);
      fixture.runner.runAll();
      assertEquals(0, fixture.store.polls.get());
      assertEquals(List.of(existing), fixture.service.list());
      assertTrue(fixture.listener.observations.isEmpty());
      assertEquals(0, fixture.publications.get());

      assertThrows(CancellationException.class, () -> fixture.service.poll().join());
      fixture.service.shutdown();
      assertEquals(0, fixture.runner.size());
      assertEquals(1, fixture.store.closes);
    }
  }

  @Test(timeout = 10000L)
  public void pollingWaitsForAnEditorLeaseAndUsesItsPublishedRevision() throws Exception {
    PanelDefinition original = panel("existing", 0.0D);
    PanelDefinition editorUpdate = original.withTransform(transform(64.0D))
        .withRevision(original.revision() + 1L);
    PanelDefinition diskUpdate = editorUpdate.withTransform(transform(128.0D))
        .withRevision(editorUpdate.revision() + 1L);

    try (Fixture fixture = new Fixture(List.of(original));
         ExecutorService executor = Executors.newSingleThreadExecutor()) {
      fixture.store.stage(List.of(diskUpdate), List.of());
      CountDownLatch workerStarted = new CountDownLatch(1);
      CompletableFuture<DocumentDelta> pending;
      Future<?> worker;
      try (GlossPersistenceCoordinator.ExternalTransaction transaction =
               fixture.coordinator.beginExternalTransaction()) {
        pending = fixture.service.poll();
        worker = executor.submit(() -> {
          workerStarted.countDown();
          fixture.runner.runNext();
        });

        assertTrue(workerStarted.await(5L, TimeUnit.SECONDS));
        assertFalse(fixture.store.pollEntered.await(100L, TimeUnit.MILLISECONDS));
        assertFalse(pending.isDone());
        assertEquals(original, fixture.service.get(original.id()).orElseThrow());
        assertEquals(editorUpdate, fixture.service.publishExternalUpdate(original, editorUpdate));
        assertEquals(editorUpdate, fixture.service.get(original.id()).orElseThrow());
      }

      worker.get(5L, TimeUnit.SECONDS);
      assertEquals(List.of(diskUpdate.id()), pending.get(5L, TimeUnit.SECONDS).loaded());
      assertEquals(diskUpdate, fixture.service.get(original.id()).orElseThrow());
      assertEquals(2, fixture.listener.observations.size());
      Observation last = fixture.listener.observations.get(1);
      assertEquals(editorUpdate, last.previous());
      assertEquals(diskUpdate, last.current());
      assertEquals(Set.of(diskUpdate), last.published());
      assertEquals(2, fixture.publications.get());
    }
  }

  private static PanelDefinition panel(String id, double x) {
    return PanelDefinition.create(id, "menu", transform(x));
  }

  private static PanelTransform transform(double x) {
    return PanelTransform.at("example:world", WORLD_UUID, x, 64.0D, 0.0D, 0.0D);
  }

  private static final class Fixture implements AutoCloseable {
    private final ControlledStore store;
    private final QueuedRunner runner = new QueuedRunner();
    private final GlossPersistenceCoordinator coordinator = new GlossPersistenceCoordinator();
    private final AtomicInteger beforePublications = new AtomicInteger();
    private final AtomicInteger publications = new AtomicInteger();
    private final PanelService service;
    private final RecordingListener listener;

    private Fixture(List<PanelDefinition> definitions) {
      store = new ControlledStore(definitions);
      Logger logger = Logger.getAnonymousLogger();
      logger.setLevel(Level.OFF);
      service = new PanelService(new PanelService.Dependencies(store, runner, logger, coordinator,
          beforePublications::incrementAndGet), publications::incrementAndGet);
      CompletableFuture<PanelLoadResult> started = service.start();
      runner.runNext();
      assertTrue(started.join().successful());
      beforePublications.set(0);
      publications.set(0);
      listener = new RecordingListener(service);
      service.addListener(listener);
    }

    @Override
    public void close() {
      service.shutdown();
    }
  }

  private static final class QueuedRunner implements StorageTaskRunner {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private boolean shutdown;

    @Override
    public synchronized StorageTaskHandle submit(Runnable task) {
      tasks.addLast(task);
      return () -> cancel(task);
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    private synchronized int size() {
      return tasks.size();
    }

    private void runNext() {
      Runnable task;
      synchronized (this) {
        task = tasks.removeFirst();
      }
      task.run();
    }

    private void runAll() {
      while (size() > 0) {
        runNext();
      }
    }

    private synchronized void cancel(Runnable task) {
      tasks.remove(task);
    }
  }

  private static final class RecordingListener implements PanelServiceListener {
    private final PanelService service;
    private final List<Observation> observations = new ArrayList<>();
    private int reloads;

    private RecordingListener(PanelService service) {
      this.service = service;
    }

    @Override
    public void boardCreated(PanelDefinition panel) {
      record("created", null, panel);
    }

    @Override
    public void boardUpdated(PanelDefinition previous, PanelDefinition updated) {
      record("updated", previous, updated);
    }

    @Override
    public void boardDeleted(PanelDefinition panel) {
      record("deleted", panel, null);
    }

    @Override
    public void boardsReloaded(PanelLoadResult result, List<PanelDefinition> panels) {
      reloads++;
    }

    private void record(String action, PanelDefinition previous, PanelDefinition current) {
      PanelDefinition target = current == null ? previous : current;
      observations.add(new Observation(action, previous, current, Set.copyOf(service.list()),
          service.maximumViewRange(), service.query(target.transform().worldUuid(),
              target.transform().x(), target.transform().z(), 0.0D),
          service.get(target.uuid()).orElse(null)));
    }

    private Observation find(String action, String id) {
      return observations.stream().filter(observation -> observation.action().equals(action)
          && (observation.current() == null ? observation.previous() : observation.current())
              .id().equals(id)).findFirst().orElseThrow();
    }
  }

  private static final class ControlledStore implements PanelStore {
    private final Map<String, PanelDefinition> definitions = new LinkedHashMap<>();
    private final ArrayDeque<PollChanges> pending = new ArrayDeque<>();
    private final AtomicInteger polls = new AtomicInteger();
    private final CountDownLatch pollEntered = new CountDownLatch(1);
    private IOException nextFailure;
    private int loads;
    private int closes;

    private ControlledStore(List<PanelDefinition> initial) {
      for (PanelDefinition definition : initial) {
        definitions.put(definition.id(), definition);
      }
    }

    @Override
    public Path directory() {
      return Path.of("panels");
    }

    @Override
    public PanelLoadResult load() {
      loads++;
      return new PanelLoadResult(definitions.size(), 0, 0, Map.of());
    }

    @Override
    public DocumentDelta poll() throws IOException {
      polls.incrementAndGet();
      pollEntered.countDown();
      if (nextFailure != null) {
        IOException failure = nextFailure;
        nextFailure = null;
        throw failure;
      }
      PollChanges changes = pending.pollFirst();
      if (changes == null) {
        return DocumentDelta.EMPTY;
      }
      for (String id : changes.removed()) {
        definitions.remove(id);
      }
      List<String> loaded = new ArrayList<>(changes.loaded().size());
      for (PanelDefinition definition : changes.loaded()) {
        definitions.put(definition.id(), definition);
        loaded.add(definition.id());
      }
      return new DocumentDelta(loaded, changes.removed());
    }

    @Override
    public void close() {
      closes++;
    }

    @Override
    public Optional<PanelDefinition> get(String id) {
      return Optional.ofNullable(definitions.get(id));
    }

    @Override
    public List<PanelDefinition> list() {
      return List.copyOf(definitions.values());
    }

    @Override
    public PanelDefinition create(PanelDefinition definition) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PanelDefinition update(String id, long expectedRevision,
                                  UnaryOperator<PanelDefinition> update) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PanelDefinition rename(String id, String newId, long expectedRevision) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PanelDefinition delete(String id, long expectedRevision) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PanelDefinition publishExternalCreate(PanelDefinition created) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PanelDefinition recoverExternalCreate(PanelDefinition created) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PanelDefinition publishExternal(PanelDefinition expected, PanelDefinition updated) {
      definitions.put(updated.id(), updated);
      return updated;
    }

    @Override
    public PanelDefinition recoverExternal(PanelDefinition applied, PanelDefinition restored) {
      throw new UnsupportedOperationException();
    }

    private void stage(List<PanelDefinition> loaded, List<String> removed) {
      pending.addLast(new PollChanges(loaded, removed));
    }
  }

  private record PollChanges(List<PanelDefinition> loaded, List<String> removed) {
  }

  private record Observation(String action, PanelDefinition previous, PanelDefinition current,
                             Set<PanelDefinition> published, double maximumViewRange,
                             List<PanelDefinition> nearby, PanelDefinition byUuid) {
  }
}
