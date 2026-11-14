package art.arcane.gloss.editor.sync;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells when a workspace file may have changed, from the platform's file events: the data folder itself (single-file
 * kinds, and kind folders that appear) and each kind folder and the images folder with every subfolder. Nothing runs
 * while nothing changes. {@link #start} declines where the JDK's watcher only polls (macOS polls every 2 s); the
 * editor then compares file times once a second instead.
 */
public final class EditorWorkspaceWatch implements AutoCloseable {
  private final Path data;
  private final WatchService service;
  private final Runnable changed;
  private final Set<String> roots = new HashSet<>();
  private final Map<WatchKey, Path> keys = new ConcurrentHashMap<>();
  private final Thread thread;
  private volatile boolean closed;

  /** Watches {@code data} with the platform's native file events; empty where the JDK would poll. */
  public static Optional<EditorWorkspaceWatch> start(Path data, Runnable changed) throws IOException {
    WatchService service = data.getFileSystem().newWatchService();
    if (service.getClass().getName().endsWith("PollingWatchService")) {
      service.close();
      return Optional.empty();
    }
    try {
      return Optional.of(new EditorWorkspaceWatch(data, service, changed));
    } catch (IOException | RuntimeException failure) {
      service.close();
      throw failure;
    }
  }

  /** Watches {@code data} with {@code service}; {@code changed} runs on the watch thread after each relevant batch. */
  public EditorWorkspaceWatch(Path data, WatchService service, Runnable changed) throws IOException {
    this.data = Objects.requireNonNull(data).toAbsolutePath().normalize();
    this.service = Objects.requireNonNull(service);
    this.changed = Objects.requireNonNull(changed);
    for (EditorSyncDocumentKind kind : EditorSyncDocumentKind.ORDERED) {
      roots.add(kind.storageName());
    }
    roots.add("images");
    register(this.data);
    for (String root : roots) {
      Path folder = this.data.resolve(root);
      if (Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
        registerTree(folder);
      }
    }
    thread = Thread.ofPlatform().daemon().name("Gloss-editor-watch").start(this::run);
  }

  private void run() {
    while (!closed) {
      WatchKey key;
      try {
        key = service.take();
      } catch (InterruptedException | ClosedWatchServiceException stopped) {
        return;
      }
      Path directory = keys.get(key);
      boolean relevant = false;
      for (WatchEvent<?> event : key.pollEvents()) {
        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
          relevant = true;
          continue;
        }
        if (directory == null || !(event.context() instanceof Path name)) {
          continue;
        }
        if (directory.equals(data) && !roots.contains(name.toString())) {
          continue;
        }
        relevant = true;
        Path child = directory.resolve(name);
        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
          registerQuietly(child);
        }
      }
      if (!key.reset()) {
        keys.remove(key);
      }
      if (relevant && !closed) {
        changed.run();
      }
    }
  }

  private void registerQuietly(Path folder) {
    try {
      registerTree(folder);
    } catch (IOException | RuntimeException gone) {
      // A folder removed as it was made; the change still refreshes the catalog.
    }
  }

  private void registerTree(Path folder) throws IOException {
    Files.walkFileTree(folder, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
        register(directory);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException failure) {
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private void register(Path directory) throws IOException {
    WatchKey key = directory.register(service, StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
    keys.put(key, directory);
  }

  @Override
  public void close() {
    closed = true;
    try {
      service.close();
    } catch (IOException ignored) {
      // Closing a watch service only releases its handles.
    }
    thread.interrupt();
  }
}
