package art.arcane.gloss.editor.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs on the JDK's default watcher, which polls every 2 s on macOS, so each step allows several seconds. */
class EditorWorkspaceWatchTest {
  @TempDir
  Path data;

  @Test
  void writesUnderTheWorkspaceAreToldAndOtherFilesAreNot() throws Exception {
    Files.createDirectories(data.resolve("holograms"));
    Semaphore told = new Semaphore(0);
    try (EditorWorkspaceWatch ignored = new EditorWorkspaceWatch(data, FileSystems.getDefault().newWatchService(),
        told::release)) {
      Files.writeString(data.resolve("gloss.toml"), "editor = true\n");
      Files.createDirectories(data.resolve("history/holograms"));
      boolean other = told.tryAcquire(3, TimeUnit.SECONDS);

      Files.writeString(data.resolve("holograms/welcome.json"), "{}");
      boolean document = told.tryAcquire(6, TimeUnit.SECONDS);
      told.drainPermits();

      Files.createDirectories(data.resolve("menus/shop"));
      Files.writeString(data.resolve("menus/shop/main.json"), "{}");
      boolean newFolder = told.tryAcquire(6, TimeUnit.SECONDS);
      Thread.sleep(2500L);
      told.drainPermits();

      Files.writeString(data.resolve("menus/shop/second.json"), "{}");
      boolean nested = told.tryAcquire(6, TimeUnit.SECONDS);

      assertFalse(other, "a file outside the workspace was told");
      assertTrue(document, "a document write was not told");
      assertTrue(newFolder, "a new kind folder was not told");
      assertTrue(nested, "a write in a folder made after the start was not told");
    }
  }
}
