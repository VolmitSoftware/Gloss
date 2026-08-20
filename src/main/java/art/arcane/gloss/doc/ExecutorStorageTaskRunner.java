package art.arcane.gloss.doc;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One daemon thread per storage service, named {@code <prefix>-<n>} so a thread dump attributes a
 * stalled write to the store that issued it.
 */
public final class ExecutorStorageTaskRunner implements StorageTaskRunner {
  private final ExecutorService executor;

  public ExecutorStorageTaskRunner(ClassLoader contextClassLoader, String threadNamePrefix) {
    ClassLoader requiredClassLoader = Objects.requireNonNull(contextClassLoader, "contextClassLoader");
    String requiredPrefix = Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");
    AtomicInteger sequence = new AtomicInteger();
    this.executor = Executors.newSingleThreadExecutor(task -> {
      Thread thread = new Thread(task, requiredPrefix + "-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      thread.setContextClassLoader(requiredClassLoader);
      return thread;
    });
  }

  @Override
  public StorageTaskHandle submit(Runnable task) {
    Future<?> future = executor.submit(Objects.requireNonNull(task, "task"));
    return () -> future.cancel(false);
  }

  @Override
  public void shutdown() {
    executor.shutdown();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return executor.awaitTermination(timeout, unit);
  }
}
