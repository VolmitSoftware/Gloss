package art.arcane.gloss.persistence;

import art.arcane.gloss.Gloss;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.logging.Level;

public final class GlossPersistenceCoordinator {
  /**
   * How long an external transaction may hold the global write permit before it is treated as
   * abandoned. Generous by design: a legitimate editor-sync publication is a handful of file writes
   * and one server-thread hop, so anything past this is a lease whose owner will never close it.
   */
  private static final long EXTERNAL_TRANSACTION_TTL_MS = 120_000L;

  private final Semaphore writePermit = new Semaphore(1, true);
  private final AtomicBoolean watcherPaused = new AtomicBoolean();
  private final AtomicBoolean recoveryRequired = new AtomicBoolean();
  private final AtomicReference<ExternalTransaction> activeTransaction = new AtomicReference<>();
  private final LongSupplier clock;

  public GlossPersistenceCoordinator() {
    this(GlossPersistenceCoordinator::monotonicMillis);
  }

  /**
   * @param monotonicMillisClock seam for tests; must never move backwards
   */
  public GlossPersistenceCoordinator(LongSupplier monotonicMillisClock) {
    this.clock = Objects.requireNonNull(monotonicMillisClock, "monotonicMillisClock");
  }

  public <T> T write(CheckedOperation<T> operation) throws Exception {
    CheckedOperation<T> requiredOperation = Objects.requireNonNull(operation, "operation");
    requireHealthy();
    abortAbandonedTransaction();
    writePermit.acquire();
    try {
      requireHealthy();
      return requiredOperation.execute();
    } finally {
      writePermit.release();
    }
  }

  public ExternalTransaction beginExternalTransaction() throws InterruptedException {
    requireHealthy();
    abortAbandonedTransaction();
    writePermit.acquire();
    if (recoveryRequired.get()) {
      writePermit.release();
      throw recoveryRequiredException();
    }
    if (!watcherPaused.compareAndSet(false, true)) {
      writePermit.release();
      throw new IllegalStateException("an external persistence transaction is already active");
    }
    ExternalTransaction transaction = new ExternalTransaction(this, clock.getAsLong());
    activeTransaction.set(transaction);
    return transaction;
  }

  public boolean tryRead(Runnable operation) {
    Runnable requiredOperation = Objects.requireNonNull(operation, "operation");
    if (recoveryRequired.get()) {
      return false;
    }
    abortAbandonedTransaction();
    if (!writePermit.tryAcquire()) {
      return false;
    }
    try {
      if (watcherPaused.get() || recoveryRequired.get()) {
        return false;
      }
      requiredOperation.run();
      return true;
    } finally {
      writePermit.release();
    }
  }

  public boolean watcherPaused() {
    return watcherPaused.get();
  }

  public void requireRestartRecovery() {
    recoveryRequired.set(true);
  }

  public boolean recoveryRequired() {
    return recoveryRequired.get();
  }

  /**
   * Releases a lease whose owner never closed it.
   *
   * <p>An external transaction owns the single write permit, so a publication that wedges — a
   * scheduler hop that is accepted and never runs, a future that never completes — parks the menu
   * hot-reload watcher, the panel queue and every later write behind it for the life of the server.
   * The check runs on the entry paths rather than on a timer: the hot-reload watchdog calls
   * {@link #tryRead} on its own IO thread every pass, so an abandoned lease is reclaimed within one
   * poll of the TTL expiring even when the server thread is the thread that is stuck.
   *
   * <p>Force-aborting takes the same {@code open} CAS a close does, so the owner's later
   * {@code close()} is a no-op and the permit is never released twice.
   */
  private void abortAbandonedTransaction() {
    ExternalTransaction active = activeTransaction.get();
    if (active == null) {
      return;
    }
    if (clock.getAsLong() - active.startedAtMillis < EXTERNAL_TRANSACTION_TTL_MS) {
      return;
    }
    if (!active.forceAbort()) {
      return;
    }
    Gloss.log(Level.SEVERE,
        "An external persistence transaction was never closed within %d ms and has been force-aborted."
            + " Editor sync or panel creation left the store locked; the affected publication did not"
            + " complete and must be retried.",
        EXTERNAL_TRANSACTION_TTL_MS);
  }

  private void requireHealthy() {
    if (recoveryRequired.get()) {
      throw recoveryRequiredException();
    }
  }

  private static IllegalStateException recoveryRequiredException() {
    return new IllegalStateException(
        "Gloss persistence is quarantined until restart recovery completes");
  }

  private static long monotonicMillis() {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
  }

  private void endExternalTransaction(ExternalTransaction transaction) {
    activeTransaction.compareAndSet(transaction, null);
    if (!watcherPaused.compareAndSet(true, false)) {
      throw new IllegalStateException("external persistence transaction is not active");
    }
    writePermit.release();
  }

  @FunctionalInterface
  public interface CheckedOperation<T> {
    T execute() throws Exception;
  }

  public static final class ExternalTransaction implements AutoCloseable {
    private final GlossPersistenceCoordinator owner;
    private final long startedAtMillis;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private ExternalTransaction(GlossPersistenceCoordinator owner, long startedAtMillis) {
      this.owner = owner;
      this.startedAtMillis = startedAtMillis;
    }

    @Override
    public void close() {
      if (open.compareAndSet(true, false)) {
        owner.endExternalTransaction(this);
      }
    }

    private boolean forceAbort() {
      if (!open.compareAndSet(true, false)) {
        return false;
      }
      owner.endExternalTransaction(this);
      return true;
    }
  }
}
