package art.arcane.gloss.persistence;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HoloUiPersistenceCoordinatorTest {
  /** Mirrors {@code GlossPersistenceCoordinator.EXTERNAL_TRANSACTION_TTL_MS}. */
  private static final long TTL_MILLIS = 120_000L;

  @Test
  public void externalTransactionPausesWatchersAndSerializesOrdinaryWriters() throws Exception {
    GlossPersistenceCoordinator coordinator = new GlossPersistenceCoordinator();
    GlossPersistenceCoordinator.ExternalTransaction transaction =
        coordinator.beginExternalTransaction();
    CountDownLatch entered = new CountDownLatch(1);
    AtomicBoolean completed = new AtomicBoolean();
    Thread writer = new Thread(() -> {
      try {
        coordinator.write(() -> {
          entered.countDown();
          completed.set(true);
          return null;
        });
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
    });
    writer.start();

    assertTrue(coordinator.watcherPaused());
    assertFalse(entered.await(100L, TimeUnit.MILLISECONDS));
    transaction.close();
    writer.join(5000L);

    assertFalse(coordinator.watcherPaused());
    assertTrue(completed.get());
  }

  @Test
  public void externalTransactionCannotBeginDuringAWatcherRead() throws Exception {
    GlossPersistenceCoordinator coordinator = new GlossPersistenceCoordinator();
    CountDownLatch readerEntered = new CountDownLatch(1);
    CountDownLatch releaseReader = new CountDownLatch(1);
    CountDownLatch transactionEntered = new CountDownLatch(1);
    Thread reader = new Thread(() -> coordinator.tryRead(() -> {
      readerEntered.countDown();
      try {
        if (!releaseReader.await(5L, TimeUnit.SECONDS)) {
          throw new AssertionError("reader release timed out");
        }
      } catch (InterruptedException interruption) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interruption);
      }
    }));
    reader.start();
    assertTrue(readerEntered.await(5L, TimeUnit.SECONDS));

    Thread transaction = new Thread(() -> {
      try (GlossPersistenceCoordinator.ExternalTransaction ignored =
               coordinator.beginExternalTransaction()) {
        transactionEntered.countDown();
      } catch (InterruptedException interruption) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interruption);
      }
    });
    transaction.start();
    assertFalse(transactionEntered.await(100L, TimeUnit.MILLISECONDS));
    releaseReader.countDown();
    reader.join(5000L);
    transaction.join(5000L);

    assertTrue(transactionEntered.getCount() == 0L);
    assertFalse(coordinator.watcherPaused());
  }

  @Test
  public void restartRecoveryQuarantineRejectsQueuedAndFuturePersistenceWork() throws Exception {
    GlossPersistenceCoordinator coordinator = new GlossPersistenceCoordinator();
    GlossPersistenceCoordinator.ExternalTransaction transaction =
        coordinator.beginExternalTransaction();
    AtomicBoolean completed = new AtomicBoolean();
    AtomicReference<Throwable> writerFailure = new AtomicReference<>();
    Thread writer = new Thread(() -> {
      try {
        coordinator.write(() -> {
          completed.set(true);
          return null;
        });
      } catch (Throwable failure) {
        writerFailure.set(failure);
      }
    });
    writer.start();

    coordinator.requireRestartRecovery();
    transaction.close();
    writer.join(TimeUnit.SECONDS.toMillis(5L));

    assertFalse(writer.isAlive());
    assertFalse(completed.get());
    assertTrue(writerFailure.get() instanceof IllegalStateException);
    assertTrue(coordinator.recoveryRequired());
    assertFalse(coordinator.tryRead(() -> {
      throw new AssertionError("quarantined watcher read ran");
    }));
    assertThrows(IllegalStateException.class, coordinator::beginExternalTransaction);
  }

  @Test
  public void anAbandonedExternalTransactionIsReclaimedSoWatcherReadsResume() throws Exception {
    AtomicLong clock = new AtomicLong();
    GlossPersistenceCoordinator coordinator = new GlossPersistenceCoordinator(clock::get);
    coordinator.beginExternalTransaction();

    AtomicBoolean read = new AtomicBoolean();
    assertFalse(coordinator.tryRead(() -> read.set(true)));
    assertFalse(read.get());
    assertTrue(coordinator.watcherPaused());

    clock.set(TTL_MILLIS - 1L);
    assertFalse(coordinator.tryRead(() -> read.set(true)));
    assertTrue(coordinator.watcherPaused());

    clock.set(TTL_MILLIS);
    assertTrue(coordinator.tryRead(() -> read.set(true)));
    assertTrue(read.get());
    assertFalse(coordinator.watcherPaused());
  }

  @Test
  public void reclaimingAnAbandonedTransactionUnblocksAWriterParkedOnThePermit() throws Exception {
    AtomicLong clock = new AtomicLong();
    GlossPersistenceCoordinator coordinator = new GlossPersistenceCoordinator(clock::get);
    coordinator.beginExternalTransaction();

    CountDownLatch written = new CountDownLatch(1);
    Thread writer = new Thread(() -> {
      try {
        coordinator.write(() -> {
          written.countDown();
          return null;
        });
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
    });
    writer.start();
    assertFalse(written.await(100L, TimeUnit.MILLISECONDS));

    clock.set(TTL_MILLIS);
    coordinator.tryRead(() -> {
    });
    assertTrue(written.await(5L, TimeUnit.SECONDS));
    writer.join(5000L);
  }

  @Test
  public void closingAReclaimedTransactionIsANoOpRatherThanADoubleRelease() throws Exception {
    AtomicLong clock = new AtomicLong();
    GlossPersistenceCoordinator coordinator = new GlossPersistenceCoordinator(clock::get);
    GlossPersistenceCoordinator.ExternalTransaction abandoned = coordinator.beginExternalTransaction();

    clock.set(TTL_MILLIS);
    assertTrue(coordinator.tryRead(() -> {
    }));
    abandoned.close();

    assertFalse(coordinator.watcherPaused());
    GlossPersistenceCoordinator.ExternalTransaction next = coordinator.beginExternalTransaction();
    assertTrue(coordinator.watcherPaused());
    next.close();
    assertFalse(coordinator.watcherPaused());
  }

  @Test
  public void aTransactionThatKeepsWithinItsTtlIsNeverReclaimed() throws Exception {
    AtomicLong clock = new AtomicLong();
    GlossPersistenceCoordinator coordinator = new GlossPersistenceCoordinator(clock::get);
    GlossPersistenceCoordinator.ExternalTransaction transaction = coordinator.beginExternalTransaction();

    for (long elapsed = 0L; elapsed < TTL_MILLIS; elapsed += TTL_MILLIS / 8L) {
      clock.set(elapsed);
      assertFalse(coordinator.tryRead(() -> {
        throw new AssertionError("a live transaction was reclaimed");
      }));
    }

    transaction.close();
    assertFalse(coordinator.watcherPaused());
    assertTrue(coordinator.tryRead(() -> {
    }));
  }
}
