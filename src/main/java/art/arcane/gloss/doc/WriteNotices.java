package art.arcane.gloss.doc;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Tells listeners that Gloss wrote or deleted a file, as it happens, so the web editor can announce the change
 * without waiting for its next scan. Listeners run on the writing thread and must return at once.
 */
public final class WriteNotices {
  private static final List<Consumer<Path>> LISTENERS = new CopyOnWriteArrayList<>();

  private WriteNotices() {
  }

  /** Adds a listener; closing the handle removes it. */
  public static AutoCloseable listen(Consumer<Path> listener) {
    Consumer<Path> added = Objects.requireNonNull(listener);
    LISTENERS.add(added);
    return () -> LISTENERS.remove(added);
  }

  /** A file at {@code path}, or something in the directory {@code path}, was written or deleted. */
  public static void touched(Path path) {
    if (path == null || LISTENERS.isEmpty()) {
      return;
    }
    for (Consumer<Path> listener : LISTENERS) {
      try {
        listener.accept(path);
      } catch (RuntimeException ignored) {
        // A notice is a hint; the editor's scan finds the change anyway.
      }
    }
  }
}
