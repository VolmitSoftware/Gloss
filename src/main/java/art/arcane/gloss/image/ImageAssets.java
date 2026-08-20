package art.arcane.gloss.image;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.volmlib.util.io.FolderWatcher;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.apache.commons.imaging.ImageFormat;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.lang3.tuple.Pair;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;

/**
 * The {@code images/} folder: the picture files menu icons decode, and the watchdog pass that
 * repaints what is on screen when one of them changes.
 *
 * <p>These are bytes an operator drops in, not documents — there is no id, no envelope and no
 * revision to compare, and a poll that read every image to decide whether it changed would decode
 * the whole folder four times a second. So this stays a plain folder watch that reports files, and
 * the {@code .json} document spine is left to the collections that are documents. It is still one
 * entry on the single {@code DataWatchdog} pass like every other collection.
 *
 * <p>The folder is never created here. A server whose operator drops in no image never grows it.
 */
public final class ImageAssets {
  public static final String KIND = "images";

  private final File imageDir;
  private final FolderWatcher watcher;

  public ImageAssets(File configDir) {
    this.imageDir = new File(configDir, KIND);
    this.watcher = new FolderWatcher(imageDir);
  }

  public void startWatching() {
    Gloss.instance.watchdog().register(KIND, this::watchTick);
  }

  public void stopWatching() {
    if (Gloss.instance != null && Gloss.instance.watchdog() != null) {
      Gloss.instance.watchdog().unregister(KIND);
    }
  }

  public Pair<ImageFormat, BufferedImage> get(String relative) throws IOException {
    File file = resolve(imageDir, relative);
    return Pair.of(Imaging.guessFormat(file), Imaging.getBufferedImage(file));
  }

  /**
   * Resolves a relative image path against the image root, refusing anything that leaves it. The
   * canonical form is compared, so a traversal and an absolute path are both rejected.
   */
  static File resolve(File imageRoot, String relative) throws IOException {
    if (imageRoot == null || relative == null || relative.isBlank()) {
      throw new FileNotFoundException(String.valueOf(relative));
    }

    File root = imageRoot.getCanonicalFile();
    File image = new File(root, relative).getCanonicalFile();
    Path rootPath = root.toPath();
    if (!image.toPath().startsWith(rootPath) || !image.isFile()) {
      throw new FileNotFoundException(relative);
    }
    return image;
  }

  /**
   * Runs on the watchdog IO thread; only the repaint needs the server context. The read is taken
   * under the persistence lease because {@code images/} is one of the collections an editor-sync
   * transaction stages and swaps.
   */
  private void watchTick() {
    GlossPersistenceCoordinator coordinator = Gloss.instance.getPersistenceCoordinator();
    if (coordinator == null) {
      pollImages();
    } else {
      coordinator.tryRead(this::pollImages);
    }
  }

  private void pollImages() {
    if (!watcher.checkModified()) {
      return;
    }
    List<File> changed = List.copyOf(watcher.getChanged());
    List<File> created = List.copyOf(watcher.getCreated());
    List<File> deleted = List.copyOf(watcher.getDeleted());
    if (changed.isEmpty() && created.isEmpty() && deleted.isEmpty()) {
      return;
    }
    boolean scheduled = SchedulerUtils.runGlobal(Gloss.instance, () -> {
      for (File file : changed) {
        Gloss.log(Level.INFO, "Image asset \"%s\" changed and was hot reloaded.", file.getName());
      }
      for (File file : created) {
        Gloss.log(Level.INFO, "Image asset \"%s\" was detected and hot loaded.", file.getName());
      }
      for (File file : deleted) {
        Gloss.log(Level.INFO, "Image asset \"%s\" was removed.", file.getName());
      }
      if (Gloss.instance.getSessionManager() != null) {
        Gloss.instance.getSessionManager().refreshVisuals();
      }
      if (Gloss.instance.getPanelRuntime() != null) {
        Gloss.instance.getPanelRuntime().refreshVisuals();
      }
    });
    if (!scheduled) {
      Gloss.log(Level.WARNING,
          "Image hot reload could not reach the server thread; %d change(s) were skipped.",
          changed.size() + created.size() + deleted.size());
    }
  }
}
