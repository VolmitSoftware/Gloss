package art.arcane.gloss.doc;

import java.util.concurrent.TimeUnit;

/**
 * The single-threaded dispatch surface every Gloss storage service serialises its file work onto.
 * Tests supply their own implementation to drive a store synchronously or to hold a task open.
 */
@FunctionalInterface
public interface StorageTaskRunner {
  StorageTaskHandle submit(Runnable task);

  default void shutdown() {
  }

  default boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return true;
  }

  @FunctionalInterface
  interface StorageTaskHandle {
    void cancel();
  }
}
