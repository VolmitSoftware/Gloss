package art.arcane.gloss.config.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.DocumentRevisionConflictException;
import art.arcane.gloss.doc.DocumentTree;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.importer.LegacyHologramImportService;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.volmlib.util.hud.HudPriority;
import art.arcane.volmlib.util.hud.HudSegment;
import art.arcane.volmlib.util.hud.HudSlot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

/**
 * The {@code menus/} collection: what is loaded, what publishes a change to the running server, and
 * the in-game and editor-sync writes that produce one.
 *
 * <p>Storage, discovery and hot reload are the generic document spine — a
 * {@link DocumentRegistry#folderTree tree registry} on the single {@code DataWatchdog} pass, exactly
 * like {@code holograms/} and {@code boards/}. Menus carry no v2 envelope, so their revision is the
 * content hash the registry already computes: a write that produces the bytes the registry is
 * already serving is not a change, which is what keeps an in-game edit from being applied twice when
 * the watcher reads that same edit back off disk.
 *
 * <p>Neither {@code menus/} nor any subdirectory of it is created here. Discovery tolerates a
 * missing root, the watcher reports the contents of a folder that appears later as creations, and
 * every write path creates the directory it needs, so an operator who never authors a menu never
 * grows the folder.
 */
public final class MenuCatalog {
  public static final String KIND = "menus";

  private static final String RELOAD_PURPOSE = "gloss:reload";
  private static final String MENU_EXTENSION = ".json";
  private static final String MENU_NOUN = "menu";
  private static final long RELOAD_TTL_MILLIS = 2500L;
  private static final long QUEUED_PUBLICATION_TIMEOUT_SECONDS = 30L;

  private final File menuDir;
  private final DocumentRegistry<MenuDefinitionData> registry;
  private final MenuMutationService menuMutations;
  private final LegacyHologramImportService legacyImporter;

  private volatile boolean acceptingMenuMutations;

  public MenuCatalog(File configDir) {
    this.menuDir = new File(configDir, KIND);
    this.registry = DocumentRegistry.folderTree(KIND, menuDir, MenuCatalog::parse,
        definition -> DocumentRegistry.UNVERSIONED);
    this.menuMutations = new MenuMutationService(Gloss.instance, configDir);
    this.legacyImporter = new LegacyHologramImportService(Gloss.instance, this, configDir);
    this.acceptingMenuMutations = true;

    registry.reload();
  }

  public void startWatching() {
    Gloss.instance.watchdog().register(KIND, this::watchTick);
  }

  public void shutdown() {
    acceptingMenuMutations = false;
    if (Gloss.instance != null && Gloss.instance.watchdog() != null) {
      Gloss.instance.watchdog().unregister(KIND);
    }
    registry.close();
    legacyImporter.shutdown();
    menuMutations.shutdown();
  }

  public Set<String> keys() {
    return registry.ids();
  }

  public boolean exists(String key) {
    return registry.get(key) != null;
  }

  public Optional<MenuDefinitionData> definition(String key) {
    return document(key).map(GlossDocument::value);
  }

  public Optional<String> source(String key) {
    return document(key).map(GlossDocument::raw);
  }

  public Optional<String> revision(String key) {
    return document(key).map(GlossDocument::contentHash);
  }

  public LegacyHologramImportService legacyImporter() {
    return legacyImporter;
  }

  public CompletableFuture<MenuDocument> mutate(String menuId, UnaryOperator<JsonObject> mutation) {
    if (!acceptingMenuMutations) {
      return stoppedMutationFuture();
    }
    String id;
    try {
      id = MenuIds.require(menuId);
    } catch (IllegalArgumentException failure) {
      return CompletableFuture.failedFuture(failure);
    }
    String expectedRevision = revision(id).orElse(null);
    if (expectedRevision == null) {
      return CompletableFuture.failedFuture(new NoSuchElementException("unknown menu: " + id));
    }
    CompletableFuture<MenuDocument> written = menuMutations.mutate(id, expectedRevision, mutation);
    return publishAfterWrite(written, id, expectedRevision, false);
  }

  public CompletableFuture<MenuDocument> copy(String sourceMenuId, String targetMenuId) {
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
    String expectedRevision = revision(sourceId).orElse(null);
    if (expectedRevision == null) {
      return CompletableFuture.failedFuture(
          new NoSuchElementException("unknown menu: " + sourceId));
    }
    CompletableFuture<MenuDocument> written = menuMutations.copy(sourceId, expectedRevision, targetId);
    return publishAfterWrite(written, targetId, null, true);
  }

  public CompletableFuture<MenuDocument> create(String menuId, String source) {
    if (!acceptingMenuMutations) {
      return stoppedMutationFuture();
    }
    String id;
    try {
      id = MenuIds.require(menuId);
    } catch (IllegalArgumentException failure) {
      return CompletableFuture.failedFuture(failure);
    }
    if (revision(id).isPresent()) {
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
    if (registry.get(id) != null) {
      throw new FileAlreadyExistsException(id);
    }
    MenuDocument expected = MenuDocumentParser.parse(id, source);
    File target = new File(menuDir, id + MENU_EXTENSION);
    if (!DocumentTree.isDocument(menuDir, target)) {
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
    String currentRevision = revision(requiredCreated.id()).orElse(null);
    if (currentRevision == null) {
      return requiredCreated;
    }
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
    registry.remove(requiredCreated.id());
    refreshBoardMenu(requiredCreated.id());
    Gloss.log(Level.INFO, "Rolled back menu config \"%s\" after hologram creation failed.",
        requiredCreated.id());
    return requiredCreated;
  }

  public CompletableFuture<List<MenuDocument>> publishEditorSyncProject(
      Map<String, String> sources, Set<String> deletedIds) {
    if (!acceptingMenuMutations) {
      return CompletableFuture.failedFuture(
          new CancellationException("menu mutation service is shut down"));
    }
    Map<String, String> requiredSources = Map.copyOf(sources);
    Set<String> requiredDeletedIds = Set.copyOf(deletedIds);
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
        for (String id : requiredDeletedIds) {
          String canonicalId = MenuIds.require(id);
          if (Gloss.instance.getSessionManager() != null) {
            Gloss.instance.getSessionManager().destroyAllType(canonicalId, player -> {
            });
          }
          registry.remove(canonicalId);
          refreshBoardMenu(canonicalId);
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
   * Runs on the watchdog IO thread: the stats, the reads and the parses happen here, and the apply —
   * which destroys menu sessions, publishes definitions and refreshes panel visuals — is handed to
   * the server context as one batch.
   *
   * <p>The read is taken under the persistence lease. {@code menus/} is one of the collections an
   * editor-sync transaction stages and swaps, so a pass that read the folder mid-publish would load
   * half a project.
   */
  private void watchTick() {
    GlossPersistenceCoordinator coordinator = Gloss.instance.getPersistenceCoordinator();
    if (coordinator == null) {
      pollMenus();
    } else {
      coordinator.tryRead(this::pollMenus);
    }
  }

  private void pollMenus() {
    Set<String> known = registry.ids();
    DocumentDelta delta = registry.poll();
    if (delta.isEmpty()) {
      return;
    }
    boolean scheduled = registry.dispatch(delta,
        task -> SchedulerUtils.runGlobal(Gloss.instance, task), () -> applyDelta(delta, known));
    if (!scheduled) {
      Gloss.warnThrottled("menu-hotload-scheduling",
          "Menu hot reload could not reach the server thread; %d change(s) will be retried.",
          delta.loaded().size() + delta.removed().size());
    }
  }

  private void applyDelta(DocumentDelta delta, Set<String> known) {
    for (String id : delta.loaded()) {
      GlossDocument<MenuDefinitionData> document = registry.get(delta, id);
      if (document == null) {
        continue;
      }
      if (known.contains(id)) {
        applyChangedMenu(id);
      } else {
        refreshBoardMenu(id);
        Gloss.log(Level.INFO, "New menu config \"%s\" detected and registered.", id);
      }
    }
    for (String id : delta.removed()) {
      unregisterMenu(id);
    }
  }

  private void applyChangedMenu(String name) {
    Gloss.instance.getSessionManager().destroyAllType(name, p -> {
      SchedulerUtils.runEntity(Gloss.instance, p, () -> {
        String notice = Gloss.instance.getLocalization().legacy(
            GlossMessages.CONFIG_RELOADED,
            MessageArgs.builder().untrusted("name", name).build()
        );
        Gloss.instance.getHudBar().publish(p, new HudSegment(RELOAD_PURPOSE, HudPriority.NOTICE, RELOAD_TTL_MILLIS, List.of(HudSlot.CENTER, HudSlot.RIGHT), notice));
      });
    });
    refreshBoardMenu(name);
    Gloss.log(Level.INFO, "Menu config \"%s\" has been changed and re-registered.", name);
  }

  private void unregisterMenu(String name) {
    Gloss.instance.getSessionManager().destroyAllType(name, p -> {
    });
    refreshBoardMenu(name);
    Gloss.log(Level.INFO, "Menu config \"%s\" has been deleted and unregistered.", name);
  }

  private void refreshBoardMenu(String menuId) {
    if (Gloss.instance.getPanelRuntime() != null) {
      Gloss.instance.getPanelRuntime().refreshMenu(menuId);
    }
  }

  private Optional<GlossDocument<MenuDefinitionData>> document(String key) {
    return Optional.ofNullable(registry.get(key));
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
      String currentRevision = revision(menuId).orElse(null);
      if (document.revision().equals(currentRevision)) {
        published.complete(document);
        return;
      }
      if (create) {
        if (currentRevision != null) {
          throw new DocumentRevisionConflictException(MENU_NOUN, menuId, "absent", currentRevision);
        }
      } else if (!expectedRevision.equals(currentRevision)) {
        throw new DocumentRevisionConflictException(MENU_NOUN, menuId, expectedRevision,
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
    registry.publish(document.id(), document.source(), document.definition());
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

  private static CompletableFuture<MenuDocument> stoppedMutationFuture() {
    return CompletableFuture.failedFuture(
        new CancellationException("menu mutation service is shut down"));
  }

  private static MenuDefinitionData parse(String fileName, String raw) {
    String id = fileName.toLowerCase(Locale.ROOT).endsWith(MENU_EXTENSION)
        ? fileName.substring(0, fileName.length() - MENU_EXTENSION.length())
        : fileName;
    return MenuDocumentParser.parse(id, raw).definition();
  }
}
