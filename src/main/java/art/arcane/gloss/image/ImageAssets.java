package art.arcane.gloss.image;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.menu.icon.TextImageRasterCache;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.io.ReactiveFolder;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import net.kyori.adventure.text.Component;
import org.apache.commons.imaging.ImageFormat;
import org.apache.commons.imaging.ImageFormats;
import org.apache.commons.imaging.ImageInfo;
import org.apache.commons.imaging.Imaging;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class ImageAssets implements AutoCloseable {
  public static final String KIND = "images";
  private final File imageDir;
  private final Supplier<GlossConfig.Images> settings;
  private final Map<String, Cached> prepared = new LinkedHashMap<>(16, 0.75F, true);
  private final Map<String, Job> pending = new LinkedHashMap<>();
  private final Set<String> deferred = new LinkedHashSet<>();
  private final AtomicBoolean repaintQueued = new AtomicBoolean();
  private final AtomicBoolean repaintNeeded = new AtomicBoolean();
  private volatile ReactiveFolder watcher;
  private ThreadPoolExecutor workers;
  private long retainedBytes;
  private long completed;
  private long refused;
  private boolean closed;
  private GlossConfig.Images activeLimits;

  public ImageAssets(File configDir, Supplier<GlossConfig.Images> settings) {
    this.imageDir = new File(Objects.requireNonNull(configDir), KIND);
    this.settings = Objects.requireNonNull(settings);
    this.activeLimits = Objects.requireNonNull(settings.get());
  }

  public synchronized void startWatching() {
    if (watcher != null) {
      return;
    }
    closed = false;
    watcher = new ReactiveFolder(imageDir, this::applyChanges, new KList<>(""), new KList<>(), new KList<>());
    Gloss.instance.watchdog().register(KIND, this::watchTick);
  }

  public void stopWatching() {
    Gloss plugin = Gloss.instance;
    if (plugin != null && plugin.watchdog() != null) {
      plugin.watchdog().unregister(KIND);
    }
    ReactiveFolder previous = watcher;
    watcher = null;
    close();
    if (previous != null) {
      previous.clear();
    }
  }

  @Override
  public void close() {
    List<Job> cancelled;
    synchronized (this) {
      closed = true;
      cancelled = List.copyOf(pending.values());
      pending.clear();
      deferred.clear();
      prepared.clear();
      retainedBytes = 0;
      if (workers != null) {
        workers.shutdownNow();
        workers = null;
      }
    }
    for (Job job : cancelled) {
      job.future().completeExceptionally(new IOException("Image preparation stopped"));
    }
  }

  public Optional<PreparedImage> prepared(String relative) throws IOException {
    refreshLimits();
    String key = key(relative);
    synchronized (this) {
      trim(activeLimits);
      Cached cached = prepared.get(key);
      if (cached != null) {
        if (cached.failure() != null) {
          throw cached.failure();
        }
        return Optional.of(cached.image());
      }
    }
    request(key);
    return Optional.empty();
  }

  public CompletableFuture<PreparedImage> request(String relative) {
    refreshLimits();
    synchronized (this) {
    String key;
    try {
      key = key(relative);
    } catch (IOException failure) {
      return CompletableFuture.failedFuture(failure);
    }
    if (closed) {
      return CompletableFuture.failedFuture(new IOException("Image preparation is stopped"));
    }
    GlossConfig.Images limits = settings.get();
    trim(limits);
    Cached cached = prepared.get(key);
    if (cached != null) {
      return cached.failure() == null ? CompletableFuture.completedFuture(cached.image())
          : CompletableFuture.failedFuture(cached.failure());
    }
    Job existing = pending.get(key);
    if (existing != null) {
      return existing.future();
    }
    if (pending.size() >= limits.maxPending()) {
      refused++;
      if (deferred.size() < limits.maxPending()) {
        deferred.add(key);
      }
      return CompletableFuture.failedFuture(new IOException("Image preparation queue is full"));
    }
    if (workers == null) {
      workers = new ThreadPoolExecutor(limits.workerThreads(), limits.workerThreads(), 30, TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(limits.maxPending()), work -> {
            Thread thread = new Thread(work, "Gloss image preparation");
            thread.setDaemon(true);
            return thread;
          });
      workers.allowCoreThreadTimeOut(true);
    }
    Job job = new Job(new CompletableFuture<>(), limits);
    pending.put(key, job);
    try {
      workers.execute(() -> prepare(key, job));
    } catch (RejectedExecutionException rejection) {
      pending.remove(key, job);
      job.future().completeExceptionally(rejection);
    }
    return job.future();
    }
  }

  public void invalidate(String relative) throws IOException {
    String key = key(relative);
    Job stale;
    synchronized (this) {
      Cached previous = prepared.remove(key);
      if (previous != null) {
        retainedBytes -= previous.weight();
      }
      stale = pending.remove(key);
      deferred.remove(key);
    }
    if (stale != null) {
      stale.future().completeExceptionally(new IOException("Image changed during preparation: " + key));
    }
  }

  private void refreshLimits() {
    GlossConfig.Images next = Objects.requireNonNull(settings.get());
    List<Job> cancelled;
    synchronized (this) {
      if (activeLimits.equals(next)) {
        return;
      }
      activeLimits = next;
      prepared.clear();
      retainedBytes = 0;
      cancelled = List.copyOf(pending.values());
      for (String path : pending.keySet()) {
        if (deferred.size() < next.maxPending()) {
          deferred.add(path);
        }
      }
      pending.clear();
      while (deferred.size() > next.maxPending()) {
        deferred.remove(deferred.iterator().next());
      }
    }
    for (Job job : cancelled) {
      job.future().completeExceptionally(new IOException("Image preparation settings changed"));
    }
    repaintNeeded.set(true);
  }

  public synchronized Snapshot snapshot() {
    return new Snapshot(prepared.size(), pending.size(), retainedBytes, completed, refused);
  }

  public void publishEditorSyncChanges() {
    List<String> requested;
    synchronized (this) {
      requested = new ArrayList<>(prepared.keySet());
      requested.addAll(pending.keySet());
    }
    for (String relative : requested) {
      try {
        invalidate(relative);
        request(relative);
      } catch (IOException failure) {
        Gloss.logExceptionStackThrottled(false, "image-invalidate", failure,
            "Cannot invalidate image %s.", relative);
      }
    }
    repaintNeeded.set(true);
    queueRepaint();
  }

  static File resolve(File imageRoot, String relative) throws IOException {
    if (imageRoot == null) {
      throw new FileNotFoundException(relative);
    }
    String key = key(relative);
    File root = imageRoot.getCanonicalFile();
    File image = new File(root, key).getCanonicalFile();
    if (!image.toPath().startsWith(root.toPath()) || !image.isFile()) {
      throw new FileNotFoundException(relative);
    }
    return image;
  }

  private static String key(String relative) throws IOException {
    if (relative == null || relative.isBlank() || relative.indexOf('\\') >= 0 || relative.indexOf('\0') >= 0) {
      throw new FileNotFoundException(String.valueOf(relative));
    }
    Path path;
    try {
      path = Path.of(relative);
    } catch (RuntimeException failure) {
      throw new IOException("Invalid image path", failure);
    }
    Path normalized = path.normalize();
    if (path.isAbsolute() || normalized.startsWith("..") || normalized.toString().isBlank()) {
      throw new FileNotFoundException(relative);
    }
    return normalized.toString().replace(File.separatorChar, '/');
  }

  private void prepare(String relative, Job job) {
    synchronized (this) {
      if (closed || pending.get(relative) != job) {
        return;
      }
    }
    PreparedImage image = null;
    IOException failure = null;
    try {
      byte[] bytes = readSnapshot(relative, job.limits());
      ImageInfo info = Imaging.getImageInfo(bytes);
      int width = info.getWidth();
      int height = info.getHeight();
      GlossConfig.Images limits = job.limits();
      if (width < 1 || height < 1 || width > limits.maxDimension() || height > limits.maxDimension()
          || (long) width * height > limits.maxPixels()) {
        throw new IOException("Image dimensions exceed configured source limits: " + width + "x" + height);
      }
      List<Component> rows = List.of();
      if (width <= limits.rasterMaxDimension() && height <= limits.rasterMaxDimension()) {
        BufferedImage decoded = Imaging.getBufferedImage(bytes);
        rows = TextImageRasterCache.prepare(decoded, info.getFormat() == ImageFormats.JPEG,
            limits.rasterMaxDimension());
      }
      FaviconPixels favicon = null;
      if (info.getFormat() == ImageFormats.PNG && width == 64 && height == 64) {
        BufferedImage decoded = Imaging.getBufferedImage(bytes);
        favicon = new FaviconPixels(decoded.getRGB(0, 0, width, height, null, 0, width));
      }
      long weight = (favicon == null ? 256L : 16640L) + relative.length() * 2L + (rows.isEmpty() ? 0L : (long) width * height * 512L);
      if (weight > limits.cacheBytes()) {
        throw new IOException("Prepared image exceeds configured cache weight: " + relative);
      }
      image = new PreparedImage(width, height, rows, weight, info.getFormat(), favicon);
    } catch (IOException | RuntimeException rejected) {
      failure = rejected instanceof IOException io ? io : new IOException("Cannot prepare image " + relative, rejected);
    }
    complete(relative, job, image, failure);
  }

  private byte[] readSnapshot(String relative, GlossConfig.Images limits) throws IOException {
    AtomicReference<byte[]> bytes = new AtomicReference<>();
    AtomicReference<IOException> failure = new AtomicReference<>();
    Runnable read = () -> {
      try {
        File source = resolve(imageDir, relative);
        if (source.length() > limits.maxFileBytes()) {
          throw new IOException("Image file exceeds configured byte limit: " + relative);
        }
        try (InputStream stream = Files.newInputStream(source.toPath())) {
          byte[] content = stream.readNBytes(limits.maxFileBytes() + 1);
          if (content.length > limits.maxFileBytes()) {
            throw new IOException("Image file exceeds configured byte limit: " + relative);
          }
          bytes.set(content);
        }
      } catch (IOException rejected) {
        failure.set(rejected);
      }
    };
    Gloss plugin = Gloss.instance;
    GlossPersistenceCoordinator coordinator = plugin == null ? null : plugin.getPersistenceCoordinator();
    if (coordinator == null) {
      read.run();
    } else if (!coordinator.tryRead(read)) {
      throw new DeferredPreparation();
    }
    if (failure.get() != null) {
      throw failure.get();
    }
    return bytes.get();
  }

  private void complete(String relative, Job job, PreparedImage image, IOException failure) {
    boolean retry = failure instanceof DeferredPreparation;
    synchronized (this) {
      if (closed || !pending.remove(relative, job)) {
        return;
      }
      if (!job.limits().equals(settings.get())) {
        failure = new IOException("Image preparation settings changed");
        retry = true;
      }
      if (retry) {
        if (deferred.size() < settings.get().maxPending()) {
          deferred.add(relative);
        }
      } else {
        long weight = image == null ? 512L + relative.length() * 2L : image.weight();
        prepared.put(relative, new Cached(image, failure, weight));
        retainedBytes += weight;
        trim(settings.get());
        completed++;
      }
    }
    if (failure == null) {
      job.future().complete(image);
    } else {
      job.future().completeExceptionally(failure);
    }
    if (retry) {
      return;
    }
    if (failure != null && watcher != null) {
      Gloss.logExceptionStackThrottled(false, "image-prepare:" + relative, failure,
          "Cannot prepare image %s.", relative);
    }
    repaintNeeded.set(true);
    queueRepaint();
  }

  private void trim(GlossConfig.Images limits) {
    while (!prepared.isEmpty() && (prepared.size() > limits.maxEntries() || retainedBytes > limits.cacheBytes())) {
      Map.Entry<String, Cached> oldest = prepared.entrySet().iterator().next();
      retainedBytes -= oldest.getValue().weight();
      prepared.remove(oldest.getKey());
    }
  }

  private void watchTick() {
    refreshLimits();
    GlossPersistenceCoordinator coordinator = Gloss.instance.getPersistenceCoordinator();
    if (coordinator == null) {
      pollImages();
    } else {
      coordinator.tryRead(this::pollImages);
    }
    List<String> retry;
    synchronized (this) {
      retry = List.copyOf(deferred);
      deferred.clear();
    }
    for (String relative : retry) {
      request(relative);
    }
    queueRepaint();
  }

  private void pollImages() {
    ReactiveFolder current = watcher;
    if (current != null) {
      current.check();
    }
  }

  private void applyChanges(KList<File> created, KList<File> changed, KList<File> deleted) {
    int count = 0;
    for (List<File> files : List.of(created, changed, deleted)) {
      for (File file : files) {
        if (file == null || isTemporaryArtifact(file)) {
          continue;
        }
        String relative = imageDir.toPath().toAbsolutePath().normalize()
            .relativize(file.toPath().toAbsolutePath().normalize()).toString().replace(File.separatorChar, '/');
        try {
          invalidate(relative);
          if (files != deleted && Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            continue;
          }
          if (files != deleted) {
            request(relative);
          }
          count++;
        } catch (IOException failure) {
          Gloss.logExceptionStackThrottled(false, "image-change", failure,
              "Cannot reload image %s.", relative);
        }
      }
    }
    if (count > 0) {
      Gloss.instance.watchdog().recordHotload(KIND, count);
      repaintNeeded.set(true);
    }
  }

  private void queueRepaint() {
    Gloss plugin = Gloss.instance;
    if (watcher == null || plugin == null || !repaintNeeded.get() || !repaintQueued.compareAndSet(false, true)) {
      return;
    }
    boolean scheduled = FoliaScheduler.runGlobal(plugin, () -> {
      try {
        if (watcher == null || !repaintNeeded.getAndSet(false)) {
          return;
        }
        if (plugin.getSessionManager() != null) {
          plugin.getSessionManager().refreshVisuals();
        }
        if (plugin.getPanelRuntime() != null) {
          plugin.getPanelRuntime().refreshVisuals();
        }
      } finally {
        repaintQueued.set(false);
      }
    }, 1L);
    if (!scheduled) {
      repaintQueued.set(false);
    }
  }

  private static boolean isTemporaryArtifact(File file) {
    String name = file.getName().toLowerCase(Locale.ROOT);
    return name.startsWith(".") || name.startsWith("~") || name.startsWith("#") || name.endsWith("~")
        || name.endsWith(".tmp") || name.endsWith(".temp") || name.endsWith(".part") || name.endsWith(".swp")
        || name.endsWith(".swx") || name.endsWith(".bak") || name.contains(".tmp.") || name.contains(".temp.");
  }

  public record PreparedImage(int width, int height, List<Component> rows, long weight,
                              ImageFormat format, FaviconPixels favicon) {
    public PreparedImage {
      rows = List.copyOf(rows);
    }
  }

  public static final class FaviconPixels {
    private final int[] pixels;

    private FaviconPixels(int[] pixels) {
      this.pixels = pixels;
    }

    public BufferedImage copyImage() {
      BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
      image.setRGB(0, 0, 64, 64, pixels, 0, 64);
      return image;
    }
  }

  public record Snapshot(int cachedEntries, int pending, long retainedWeight, long completed, long refused) {
  }

  private record Cached(PreparedImage image, IOException failure, long weight) {
  }

  private record Job(CompletableFuture<PreparedImage> future, GlossConfig.Images limits) {
  }

  private static final class DeferredPreparation extends IOException {
  }
}
