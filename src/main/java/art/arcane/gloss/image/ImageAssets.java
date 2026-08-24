package art.arcane.gloss.image;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.io.ReactiveFolder;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.apache.commons.imaging.ImageFormat;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.lang3.tuple.Pair;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * The {@code images/} folder: the picture files menu icons decode, and the watchdog pass that
 * repaints what is on screen when one of them changes.
 *
 * <p>These are bytes an operator drops in, not documents — there is no id, no envelope and no
 * revision to compare. The reactive folder hashes bytes on a rolling budget without decoding the
 * images, while the {@code .json} document spine remains reserved for actual documents. It is one
 * entry on the single {@code DataWatchdog} pass like every other collection.
 *
 * <p>The folder is never created here. A server whose operator drops in no image never grows it.
 */
public final class ImageAssets {
  public static final String KIND = "images";

  private final File imageDir;
  private volatile ReactiveFolder watcher;

  public ImageAssets(File configDir) {
    this.imageDir = new File(configDir, KIND);
  }

  public synchronized void startWatching() {
    if (watcher != null) {
      return;
    }
    watcher = new ReactiveFolder(
        imageDir,
        this::applyChanges,
        new KList<>(""),
        new KList<>(),
        new KList<>()
    );
    Gloss.instance.watchdog().register(KIND, this::watchTick);
  }

  public synchronized void stopWatching() {
    if (Gloss.instance != null && Gloss.instance.watchdog() != null) {
      Gloss.instance.watchdog().unregister(KIND);
    }
    ReactiveFolder previous = watcher;
    watcher = null;
    if (previous != null) {
      previous.clear();
    }
  }

  public Pair<ImageFormat, BufferedImage> get(String relative) throws IOException {
    File file = resolve(imageDir, relative);
    return Pair.of(Imaging.guessFormat(file), Imaging.getBufferedImage(file));
  }

  public void publishEditorSyncChanges() {
    if (Gloss.instance.getSessionManager() != null) {
      Gloss.instance.getSessionManager().refreshVisuals();
    }
    if (Gloss.instance.getPanelRuntime() != null) {
      Gloss.instance.getPanelRuntime().refreshVisuals();
    }
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
    File image;
    try {
      image = new File(root, relative).getCanonicalFile();
    } catch (IOException rejectedByTheFilesystem) {
      // Joining the root with an absolute path produces a malformed path on Windows rather than
      // one that merely sits outside the root. Either way it is not an image inside the root.
      throw new FileNotFoundException(relative);
    }
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
    ReactiveFolder current = watcher;
    if (current == null) {
      return;
    }
    current.check();
  }

  private void applyChanges(KList<File> createdFiles, KList<File> changedFiles, KList<File> deletedFiles) {
    List<File> changed = withoutTemporaryArtifacts(changedFiles);
    List<File> created = withoutTemporaryArtifacts(createdFiles);
    List<File> deleted = withoutTemporaryArtifacts(deletedFiles);
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
      Gloss.instance.watchdog().recordHotload(KIND, changed.size() + created.size() + deleted.size());
    });
    if (!scheduled) {
      throw new IllegalStateException("Image hot reload could not reach the server thread; "
          + (changed.size() + created.size() + deleted.size()) + " change(s) remain queued");
    }
  }

  private List<File> withoutTemporaryArtifacts(List<File> files) {
    List<File> filtered = new ArrayList<>(files.size());
    for (File file : files) {
      if (file == null || isTemporaryArtifact(file)) {
        continue;
      }
      filtered.add(file);
    }
    return List.copyOf(filtered);
  }

  private boolean isTemporaryArtifact(File file) {
    String name = file.getName().toLowerCase(Locale.ROOT);
    return name.startsWith(".")
        || name.startsWith("~")
        || name.startsWith("#")
        || name.endsWith("~")
        || name.endsWith(".tmp")
        || name.endsWith(".temp")
        || name.endsWith(".part")
        || name.endsWith(".swp")
        || name.endsWith(".swx")
        || name.endsWith(".bak")
        || name.contains(".tmp.")
        || name.contains(".temp.");
  }
}
