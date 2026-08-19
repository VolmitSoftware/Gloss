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
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.stream.Stream;

public final class ConfigManager {

  private static final String RELOAD_PURPOSE = "gloss:reload";
  private static final String MENU_EXTENSION = ".json";
  private static final long RELOAD_TTL_MILLIS = 2500L;
  private static final String WATCHDOG_ENTRY = "menus";

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

  private void fileTick() {
    fastFileTick();
    slowFileTick();
  }

  private void fastFileTick() {
    if (menuDefinitionFolder.checkModifiedFast()) {
      menuDefinitionFolder.getChanged().forEach(f -> {
        if (isMenuFile(menuDir, f)) {
          String name = menuId(menuDir, f);
          String previousRevision = getRevision(name).orElse(null);
          Optional<MenuDocument> loaded = loadConfig(name, f);
          loaded.filter(document -> !document.revision().equals(previousRevision)).ifPresent(document -> {
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
          });
        }
      });
      menuDefinitionFolder.getCreated().forEach(this::registerCreatedMenus);
      menuDefinitionFolder.getDeleted().forEach(this::unregisterDeletedPath);
    }
    if (imageFolder.checkModifiedFast()) {
      if (!imageFolder.getChanged().isEmpty()) {
        imageFolder.getChanged().forEach(f -> Gloss.log(Level.INFO, "Image asset \"%s\" changed and was hot reloaded.", f.getName()));
        if (Gloss.instance.getSessionManager() != null) {
          Gloss.instance.getSessionManager().refreshVisuals();
        }
        if (Gloss.instance.getPanelRuntime() != null) {
          Gloss.instance.getPanelRuntime().refreshVisuals();
        }
      }
    }
  }

  private void slowFileTick() {
    if (menuDefinitionFolder.checkModified()) {
      menuDefinitionFolder.getCreated().forEach(this::registerCreatedMenus);
      menuDefinitionFolder.getDeleted().forEach(this::unregisterDeletedPath);
    }
    if (imageFolder.checkModified()) {
      if (!imageFolder.getCreated().isEmpty()) {
        imageFolder.getCreated().forEach(f -> Gloss.log(Level.INFO, "Image asset \"%s\" was detected and hot loaded.", f.getName()));
      }
      if (!imageFolder.getDeleted().isEmpty()) {
        imageFolder.getDeleted().forEach(f -> Gloss.log(Level.INFO, "Image asset \"%s\" was removed.", f.getName()));
      }
      if ((!imageFolder.getCreated().isEmpty() || !imageFolder.getDeleted().isEmpty()) && Gloss.instance.getSessionManager() != null) {
        Gloss.instance.getSessionManager().refreshVisuals();
        if (Gloss.instance.getPanelRuntime() != null) {
          Gloss.instance.getPanelRuntime().refreshVisuals();
        }
      }
    }
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
    String name = menuId(menuDir, f);
    Optional<MenuDocument> loaded = loadConfig(name, f);
    loaded.filter(document -> !document.revision().equals(getRevision(name).orElse(null))).ifPresent(document -> {
      publishDefinition(document);
      refreshBoardMenu(name);
      Gloss.log(Level.INFO, "New menu config \"%s\" detected and registered.", name);
    });
  }

  private void registerCreatedMenus(File path) {
    for (File created : discoverMenuFiles(path)) {
      if (isMenuFile(menuDir, created)) {
        registerMenu(created);
      }
    }
  }

  private void unregisterDeletedPath(File path) {
    if (isMenuFile(menuDir, path)) {
      unregisterMenu(path);
    }
    unregisterMenuPrefix(path);
  }

  private void unregisterMenu(File f) {
    String name = menuId(menuDir, f);
    if (menuRegistry.containsKey(name)) {
      Gloss.instance.getSessionManager().destroyAllType(name, p -> {
      });
      menuRegistry.remove(name);
      menuSourceRegistry.remove(name);
      refreshBoardMenu(name);
      Gloss.log(Level.INFO, "Menu config \"%s\" has been deleted and unregistered.", name);
    }
  }

  private void unregisterMenuPrefix(File directory) {
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
    List<String> removed = menuRegistry.keySet().stream()
        .filter(id -> id.startsWith(nestedPrefix))
        .toList();
    for (String id : removed) {
      Gloss.instance.getSessionManager().destroyAllType(id, player -> {
      });
      menuRegistry.remove(id);
      menuSourceRegistry.remove(id);
      refreshBoardMenu(id);
      Gloss.log(Level.INFO, "Menu config \"%s\" has been deleted and unregistered.", id);
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
    CompletableFuture<List<MenuDocument>> publication = new CompletableFuture<>();
    boolean accepted = SchedulerUtils.runGlobal(Gloss.instance, () -> {
      if (publication.isDone()) {
        return;
      }
      try {
        List<MenuDocument> documents = new ArrayList<>(requiredSources.size());
        for (Map.Entry<String, String> entry : requiredSources.entrySet()) {
          documents.add(MenuDocumentParser.parse(entry.getKey(), entry.getValue()));
        }
        for (MenuDocument document : documents) {
          publishDefinition(document);
          if (Gloss.instance.getSessionManager() != null) {
            Gloss.instance.getSessionManager().destroyAllType(document.id(), player -> {
            });
          }
          refreshBoardMenu(document.id());
        }
        publication.complete(List.copyOf(documents));
      } catch (RuntimeException failure) {
        publication.completeExceptionally(failure);
      }
    });
    if (!accepted) {
      publication.completeExceptionally(
          new IllegalStateException("unable to schedule editor sync menu publication"));
    }
    return publication;
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
    return published;
  }

  private void publishWrittenDocument(CompletableFuture<MenuDocument> published, MenuDocument document,
                                      String menuId, String expectedRevision, boolean create) {
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
