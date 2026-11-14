package art.arcane.gloss.doc;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keeps in-game document saves out of a publish's check-then-replace: a save waits while a publish compares the files
 * with what it was based on and moves its own into place. Held only around file operations, never while waiting on
 * another thread.
 */
public final class DataFileFence {
  private static final ReentrantLock LOCK = new ReentrantLock();

  private DataFileFence() {
  }

  public static <T> T hold(Work<T> work) throws IOException {
    LOCK.lock();
    try {
      return work.run();
    } finally {
      LOCK.unlock();
    }
  }

  @FunctionalInterface
  public interface Work<T> {
    T run() throws IOException;
  }
}
