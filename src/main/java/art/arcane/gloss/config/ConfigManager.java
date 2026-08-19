package art.arcane.gloss.config;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.menu.MenuDocument;
import art.arcane.gloss.config.menu.MenuDocumentParser;
import art.arcane.gloss.config.menu.MenuIds;
import art.arcane.gloss.config.menu.MenuMutationService;
import art.arcane.gloss.config.menu.MenuRevisionConflictException;
import art.arcane.gloss.importer.LegacyHologramImportService;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.volmlib.util.hud.HudPriority;
import art.arcane.volmlib.util.hud.HudSegment;
import art.arcane.volmlib.util.hud.HudSlot;
import art.arcane.volmlib.util.io.FolderWatcher;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import com.google.gson.JsonObject;
import org.apache.commons.imaging.ImageFormat;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.Sound;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.stream.Stream;

public final class ConfigManager {

  private static final String RELOAD_PURPOSE = "gloss:reload";
  private static final String MENU_EXTENSION = ".json";
  private static final long RELOAD_TTL_MILLIS = 2500L;
  private static final String WATCHDOG_ENTRY = "menus";
  private static final long QUEUED_PUBLICATION_TIMEOUT_SECONDS = 30L;

  private final Map<String, MenuDefinitionData> menuRegistry = new ConcurrentHashMap<>();
  private final Map<String, String> menuSourceRegistry = new ConcurrentHashMap<>();

  private final File menuDir, imageDir;
  private final FolderWatcher menuDefinitionFolder, imageFolder;
  private final MenuMutationService menuMutations;
  private final LegacyHologramImportService legacyImporter;

  private volatile boolean acceptingMenuMutations;

  public ConfigManager(File configDir) {
    this.imageDir = new File(configDir, "images");
    if (!imageDir.exists())
      imageDir.mkdirs();
    this.menuDir = new File(configDir, "menus");
    if (!menuDir.exists())
      menuDir.mkdirs();

    menuMutations = new MenuMutationService(Gloss.instance, configDir);
    legacyImporter = new LegacyHologramImportService(Gloss.instance, this, configDir);
    menuDefinitionFolder = new FolderWatcher(menuDir);
    imageFolder = new FolderWatcher(imageDir);
    acceptingMenuMutations = true;

    scanMenus();
  }

  public void startWatching() {
    Gloss.instance.watchdog().register(WATCHDOG_ENTRY, this::watchTick);
  }

  private void scanMenus() {
    for (File file : discoverMenuFiles(menuDir)) {
      registerMenu(file);
    }
  }

  private void watchTick() {
    GlossPersistenceCoordinator coordinator = Gloss.instance.getPersistenceCoordinator();
    if (coordinator == null) {
      fileTick();
    } else {
      coordinator.tryRead(this::fileTick);
    }
    Gloss.instance.getLocalization().update();
  }

  /**
   * One {@code checkModified} pass per watched folder, handling changed, created and deleted
   * together.
   *
   * <p>This used to be a fast pass followed by a slow pass over the same two watchers. Both passes
   * consume the same modification deltas, so whichever ran first ate the change: a creation seen by
   * the fast pass — which only walks children it already knows and so cannot see a new file at all —
   * left the slow pass with nothing to register, and the double walk paid two directory listings per
   * poll for it. A single pass reading all three lists cannot drop anything.
   *
   * <p>Runs on the watchdog IO thread: the stats, the reads and the parses happen here, and the
   * collected applications — which destroy menu sessions, publish definitions and refresh panel
   * visuals — are handed to the server context as one batch.
   */
  private void fileTick() {
    List<Runnable> applications = new ArrayList<>();
    collectMenuChanges(applications);
    collectImageChanges(applications);
    if (applications.isEmpty()) {
      return;
    }
    boolean scheduled = SchedulerUtils.runGlobal(Gloss.instance, () -> {
      for (Runnable application : applications) {
        application.run();
      }
    });
    if (!scheduled) {
      Gloss.log(Level.WARNING,
          "Menu hot reload could not reach the server thread; %d change(s) were skipped.",
          applications.size());
    }
  }

  private void collectMenuChanges(List<Runnable> applications) {
    if (!menuDefinitionFolder.checkModified()) {
      return;
    }
    for (File changed : menuDefinitionFolder.getChanged()) {
      if (!isMenuFile(menuDir, changed)) {
        continue;
      }
      String name = menuId(menuDir, changed);
      String previousRevision = getRevision(name).orElse(null);
      loadConfig(name, changed)
          .filter(document -> !document.revision().equals(previousRevision))
          .ifPresent(document -> applications.add(() -> applyChangedMenu(name, document)));
    }
    for (File created : menuDefinitionFolder.getCreated()) {
      collectCreatedMenus(created, applications);
    }
    for (File deleted : menuDefinitionFolder.getDeleted()) {
      collectDeletedPath(deleted, applications);
    }
  }

  private void collectImageChanges(List<Runnable> applications) {
    if (!imageFolder.checkModified()) {
      return;
    }
    List<File> changed = List.copyOf(imageFolder.getChanged());
    List<File> created = List.copyOf(imageFolder.getCreated());
    List<File> deleted = List.copyOf(imageFolder.getDeleted());
    if (changed.isEmpty() && created.isEmpty() && deleted.isEmpty()) {
      return;
    }
    applications.add(() -> {
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
  }

  private void applyChangedMenu(String name, MenuDocument document) {
    Gloss.instance.getSessionManager().destroyAllType(name, p -> {
      SchedulerUtils.runEntity(Gloss.instance, p, () -> {
        String notice = Gloss.instance.getLocalization().legacy(
            GlossMessages.CONFIG_RELOADED,
            MessageArgs.builder().untrusted("name", name).build()
        );
        Gloss.instance.getHudBar().publish(p, new HudSegment(RELOAD_PURPOSE, HudPriority.NOTICE, RELOAD_TTL_MILLIS, List.of(HudSlot.CENTER, HudSlot.RIGHT), notice));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, .5F, 1);
      });
    });
    publishDefinition(document);
    refreshBoardMenu(name);
    Gloss.log(Level.INFO, "Menu config \"%s\" has been changed and re-registered.", name);
  }

  static boolean isMenuFile(File root, File file) {
    if (root == null || file == null) {
      return false;
    }
    if (!file.getName().toLowerCase(Locale.ROOT).endsWith(MENU_EXTENSION)) {
      return false;
    }
    Path rootPath = root.toPath().toAbsolutePath().normalize();
    Path filePath = file.toPath().toAbsolutePath().normalize();
    if (!filePath.startsWith(rootPath) || filePath.equals(rootPath)
        || Files.isSymbolicLink(rootPath)) {
      return false;
    }
    Path relative = rootPath.relativize(filePath);
    Path current = rootPath;
    int segmentIndex = 0;
    for (Path segment : relative) {
      if (segment.toString().startsWith(".")) {
        return false;
      }
      current = current.resolve(segment);
      segmentIndex++;
      if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      if (Files.isSymbolicLink(current)) {
        return false;
      }
      boolean target = segmentIndex == relative.getNameCount();
      if ((target && !Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS))
          || (!target && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))) {
        return false;
      }
    }
    return true;
  }

  static List<File> discoverMenuFiles(File root) {
    if (root == null || !root.exists()) {
      return List.of();
    }
    if (root.isFile()) {
      return isMenuFile(root.getParentFile(), root) ? List.of(root) : List.of();
    }

    List<File> menus = new ArrayList<>();
    try (Stream<Path> candidates = Files.walk(root.toPath())) {
      Iterator<Path> iterator = candidates.iterator();
      while (iterator.hasNext()) {
        Path candidate = iterator.next();
        File file = candidate.toFile();
        if (isMenuFile(root, file)) {
          menus.add(file);
        }
      }
    } catch (IOException failure) {
      Gloss.logExceptionStack(true, failure, "Unable to scan menu files below %s.", root);
    }
    menus.sort(Comparator.comparing(file -> menuId(root, file)));
    return List.copyOf(menus);
  }

  static String menuId(File root, File file) {
    Path rootPath = root.toPath().toAbsolutePath().normalize();
    Path filePath = file.toPath().toAbsolutePath().normalize();
    if (!filePath.startsWith(rootPath) || filePath.equals(rootPath)) {
      throw new IllegalArgumentException("Menu file is not resolvable inside the menu root");
    }
    Path relative = rootPath.relativize(filePath);
    String path = relative.toString().replace(File.separatorChar, '/');
    return path.substring(0, path.length() - MENU_EXTENSION.length());
  }

  private void registerMenu(File f) {
    loadCreatedMenu(f).ifPresent(Runnable::run);
  }

  private Optional<Runnable> loadCreatedMenu(File f) {
    String name = menuId(menuDir, f);
    String previousRevision = getRevision(name).orElse(null);
    return loadConfig(name, f)
        .filter(document -> !document.revision().equals(previousRevision))
        .map(document -> () -> {
          publishDefinition(document);
          refreshBoardMenu(name);
          Gloss.log(Level.INFO, "New menu config \"%s\" detected and registered.", name);
        });
  }

  private void collectCreatedMenus(File path, List<Runnable> applications) {
    for (File created : discoverMenuFiles(path)) {
      if (isMenuFile(menuDir, created)) {
        loadCreatedMenu(created).ifPresent(applications::add);
      }
    }
  }

  private void collectDeletedPath(File path, List<Runnable> applications) {
    if (isMenuFile(menuDir, path)) {
      String name = menuId(menuDir, path);
      if (menuRegistry.containsKey(name)) {
        applications.add(() -> unregisterMenu(name));
      }
    }
    collectDeletedPrefix(path, applications);
  }

  private void unregisterMenu(String name) {
    if (!menuRegistry.containsKey(name)) {
      return;
    }
    Gloss.instance.getSessionManager().destroyAllType(name, p -> {
    });
    menuRegistry.remove(name);
    menuSourceRegistry.remove(name);
    refreshBoardMenu(name);
    Gloss.log(Level.INFO, "Menu config \"%s\" has been deleted and unregistered.", name);
  }

  private void collectDeletedPrefix(File directory, List<Runnable> applications) {
    String prefix;
    try {
      Path relative = menuDir.getCanonicalFile().toPath().relativize(directory.getCanonicalFile().toPath());
      prefix = relative.toString().replace(File.separatorChar, '/');
    } catch (IOException | IllegalArgumentException failure) {
      return;
    }
    if (prefix.isBlank() || prefix.startsWith("..")) {
      return;
    }
    String nestedPrefix = prefix + "/";
    for (String id : menuRegistry.keySet().stream().filter(key -> key.startsWith(nestedPrefix)).toList()) {
      applications.add(() -> unregisterMenu(id));
    }
  }

  public void shutdown() {
    acceptingMenuMutations = false;
    if (Gloss.instance != null && Gloss.instance.watchdog() != null) {
      Gloss.instance.watchdog().unregister(WATCHDOG_ENTRY);
    }
    legacyImporter.shutdown();
    menuMutations.shutdown();
  }

  private void refreshBoardMenu(String menuId) {
    if (Gloss.instance.getPanelRuntime() != null) {
      Gloss.instance.getPanelRuntime().refreshMenu(menuId);
    }
  }

  public Set<String> keys() {
    return menuRegistry.keySet();
  }

  public LegacyHologramImportService getLegacyImporter() {
    return legacyImporter;
  }

  public Optional<MenuDefinitionData> get(String key) {
    return exists(key) ? Optional.of(menuRegistry.get(key)) : Optional.empty();
  }

  public Optional<String> getSource(String key) {
    return Optional.ofNullable(menuSourceRegistry.get(key));
  }

  public Optional<String> getRevision(String key) {
    return getSource(key).map(MenuDocument::revisionOf);
  }

  public CompletableFuture<MenuDocument> mutateMenu(String menuId,
                                                    UnaryOperator<JsonObject> mutation) {
    if (!acceptingMenuMutations) {
      return stoppedMutationFuture();
    }
    String id;
    try {
      id = MenuIds.require(menuId);
    } catch (IllegalArgumentException failure) {
      return CompletableFuture.failedFuture(failure);
    }
    String expectedRevision = getRevision(id).orElse(null);
    if (expectedRevision == null) {
      return CompletableFuture.failedFuture(new NoSuchElementException("unknown menu: " + id));
    }
    CompletableFuture<MenuDocument> written = menuMutations.mutate(id, expectedRevision, mutation);
    return publishAfterWrite(written, id, expectedRevision, false);
  }

  public CompletableFuture<MenuDocument> copyMenu(String sourceMenuId, String targetMenuId) {
    if (!acceptingMenuMutations) {
      return stoppedMutationFuture();
    }
    String sourceId;
    String targetId;
    try {
      sourceId = MenuIds.require(sourceMenuId);
      targetId = MenuIds.require(targetMenuId);
    } catch (IllegalArgumentException failure) {
      return CompletableFuture.failedFuture(failure);
    }
    String expectedRevision = getRevision(sourceId).orElse(null);
    if (expectedRevision == null) {
      return CompletableFuture.failedFuture(
          new NoSuchElementException("unknown menu: " + sourceId));
    }
    CompletableFuture<MenuDocument> written = menuMutations.copy(sourceId, expectedRevision, targetId);
    return publishAfterWrite(written, targetId, null, true);
  }

  public CompletableFuture<MenuDocument> createMenu(String menuId, String source) {
    if (!acceptingMenuMutations) {
      return stoppedMutationFuture();
    }
    String id;
    try {
      id = MenuIds.require(menuId);
    } catch (IllegalArgumentException failure) {
      return CompletableFuture.failedFuture(failure);
    }
    if (getRevision(id).isPresent()) {
      return CompletableFuture.failedFuture(new FileAlreadyExistsException(id));
    }
    CompletableFuture<MenuDocument> written = menuMutations.create(id, source);
    return publishAfterWrite(written, id, null, true);
  }

  public MenuDocument publishExternalCreate(String menuId, String source) throws IOException {
    if (!acceptingMenuMutations) {
      throw new CancellationException("menu mutation service is shut down");
    }
    String id = MenuIds.require(menuId);
    if (menuSourceRegistry.containsKey(id)) {
      throw new FileAlreadyExistsException(id);
    }
    MenuDocument expected = MenuDocumentParser.parse(id, source);
    File target = new File(menuDir, id + MENU_EXTENSION);
    if (!isMenuFile(menuDir, target)) {
      throw new IOException("external menu creation is not a regular menu file: " + target);
    }
    String persistedSource = Files.readString(target.toPath(), StandardCharsets.UTF_8);
    MenuDocument persisted = MenuDocumentParser.parse(id, persistedSource);
    if (!persisted.revision().equals(expected.revision())) {
      throw new IOException("external menu creation does not match the persisted document");
    }
    publishDefinition(persisted);
    refreshBoardMenu(id);
    Gloss.log(Level.INFO, "Menu config \"%s\" was created with a persistent hologram.", id);
    return persisted;
  }

  public MenuDocument recoverExternalCreate(MenuDocument created) throws IOException {
    MenuDocument requiredCreated = Objects.requireNonNull(created, "created");
    String currentSource = menuSourceRegistry.get(requiredCreated.id());
    if (currentSource == null) {
      return requiredCreated;
    }
    String currentRevision = MenuDocument.revisionOf(currentSource);
    if (!requiredCreated.revision().equals(currentRevision)) {
      throw new IOException("cannot recover menu creation after an unrelated publication");
    }
    File target = new File(menuDir, requiredCreated.id() + MENU_EXTENSION);
    if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("recovered menu creation file still exists: " + target);
    }
    if (Gloss.instance.getSessionManager() != null) {
      Gloss.instance.getSessionManager().destroyAllType(requiredCreated.id(), player -> {
      });
    }
    menuRegistry.remove(requiredCreated.id());
    menuSourceRegistry.remove(requiredCreated.id());
    refreshBoardMenu(requiredCreated.id());
    Gloss.log(Level.INFO, "Rolled back menu config \"%s\" after hologram creation failed.",
        requiredCreated.id());
    return requiredCreated;
  }

  public CompletableFuture<List<MenuDocument>> publishEditorSyncProject(Map<String, String> sources) {
    if (!acceptingMenuMutations) {
      return CompletableFuture.failedFuture(
          new CancellationException("menu mutation service is shut down"));
    }
    Map<String, String> requiredSources = Map.copyOf(sources);
    List<MenuDocument> documents;
    try {
      documents = new ArrayList<>(requiredSources.size());
      for (Map.Entry<String, String> entry : requiredSources.entrySet()) {
        documents.add(MenuDocumentParser.parse(entry.getKey(), entry.getValue()));
      }
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
    List<MenuDocument> parsed = List.copyOf(documents);
    CompletableFuture<List<MenuDocument>> publication = new CompletableFuture<>();
    boolean accepted = SchedulerUtils.runGlobal(Gloss.instance, () -> {
      if (publication.isDone()) {
        return;
      }
      try {
        for (MenuDocument document : parsed) {
          publishDefinition(document);
          if (Gloss.instance.getSessionManager() != null) {
            Gloss.instance.getSessionManager().destroyAllType(document.id(), player -> {
            });
          }
          refreshBoardMenu(document.id());
        }
        publication.complete(parsed);
      } catch (RuntimeException failure) {
        publication.completeExceptionally(failure);
      }
    });
    if (!accepted) {
      publication.completeExceptionally(
          new IllegalStateException("unable to schedule editor sync menu publication"));
    }
    return guardQueued(publication);
  }

  /**
   * Caps how long a caller waits on a hop that was accepted but never ran.
   *
   * <p>The editor-sync apply path holds the global persistence lease across this future. A server
   * that shuts down between the scheduler accepting the task and running it leaves the future
   * pending forever, the lease unclosed, and every later menu, panel and watchdog read parked behind
   * the semaphore. Failing the future instead drives the caller's existing rollback, which closes
   * the lease. The runnable re-checks {@code isDone()} before it publishes, so a task that arrives
   * after the timeout is a no-op rather than a late mutation.
   */
  private static <T> CompletableFuture<T> guardQueued(CompletableFuture<T> queued) {
    return queued.orTimeout(QUEUED_PUBLICATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  public boolean exists(String key) {
    return menuRegistry.containsKey(key);
  }

  public Pair<ImageFormat, BufferedImage> getImage(String relative) throws IOException {
    File f = resolveImageFile(imageDir, relative);
    ImageFormat format = Imaging.guessFormat(f);
    return Pair.of(format, Imaging.getBufferedImage(f));
  }

  public List<BufferedImage> getImages(String relative) throws IOException {
    File f = resolveImageFile(imageDir, relative);
    return Imaging.getAllBufferedImages(f);
  }

  static File resolveImageFile(File imageRoot, String relative) throws IOException {
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

  private Optional<MenuDocument> loadConfig(String menuName, File f) {
    try {
      String source = Files.readString(f.toPath(), StandardCharsets.UTF_8);
      if (source.isEmpty()) {
        Gloss.log(Level.WARNING, "Menu config \"%s.json\" is empty, ignoring.", menuName);
        return Optional.empty();
      }

      return Optional.of(MenuDocumentParser.parse(menuName, source));
    } catch (Throwable ex) {
      Gloss.logExceptionStack(false, ex, "An error occurred while parsing menu config \"%s.json\":", menuName);
    }
    return Optional.empty();
  }

  private CompletableFuture<MenuDocument> publishAfterWrite(CompletableFuture<MenuDocument> written,
                                                            String menuId,
                                                            String expectedRevision,
                                                            boolean create) {
    CompletableFuture<MenuDocument> published = new CompletableFuture<>();
    written.whenComplete((document, failure) -> {
      if (failure != null) {
        published.completeExceptionally(failure);
        return;
      }
      if (!acceptingMenuMutations) {
        published.completeExceptionally(
            new CancellationException("menu mutation service shut down before publication"));
        return;
      }
      boolean accepted = SchedulerUtils.runGlobal(Gloss.instance,
          () -> publishWrittenDocument(published, document, menuId, expectedRevision, create));
      if (!accepted) {
        published.completeExceptionally(
            new IllegalStateException("unable to schedule menu publication for " + menuId));
      }
    });
    return guardQueued(published);
  }

  private void publishWrittenDocument(CompletableFuture<MenuDocument> published, MenuDocument document,
                                      String menuId, String expectedRevision, boolean create) {
    if (published.isDone()) {
      return;
    }
    try {
      if (!acceptingMenuMutations) {
        throw new CancellationException("menu mutation service shut down before publication");
      }
      String currentSource = menuSourceRegistry.get(menuId);
      String currentRevision = currentSource == null ? null : MenuDocument.revisionOf(currentSource);
      if (document.revision().equals(currentRevision)) {
        published.complete(document);
        return;
      }
      if (create) {
        if (currentRevision != null) {
          throw new MenuRevisionConflictException(menuId, "absent", currentRevision);
        }
      } else if (!expectedRevision.equals(currentRevision)) {
        throw new MenuRevisionConflictException(menuId, expectedRevision,
            currentRevision == null ? "absent" : currentRevision);
      }
      publishDefinition(document);
      if (Gloss.instance.getSessionManager() != null) {
        Gloss.instance.getSessionManager().destroyAllType(menuId, player -> {
        });
      }
      refreshBoardMenu(menuId);
      Gloss.log(Level.INFO, "Menu config \"%s\" was updated by an in-game content command.", menuId);
      published.complete(document);
    } catch (RuntimeException failure) {
      published.completeExceptionally(failure);
    }
  }

  private void publishDefinition(MenuDocument document) {
    menuRegistry.put(document.id(), document.definition());
    menuSourceRegistry.put(document.id(), document.source());
  }

  private static CompletableFuture<MenuDocument> stoppedMutationFuture() {
    return CompletableFuture.failedFuture(
        new CancellationException("menu mutation service is shut down"));
  }
}
