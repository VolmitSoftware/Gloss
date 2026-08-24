package art.arcane.gloss.service;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.panel.PanelDefinition;
import art.arcane.gloss.panel.PanelIds;
import art.arcane.gloss.panel.PanelRepository;
import art.arcane.gloss.panel.PanelTransform;
import art.arcane.gloss.config.menu.MenuBaselines;
import art.arcane.gloss.config.menu.MenuDocument;
import art.arcane.gloss.config.menu.MenuDocumentParser;
import art.arcane.gloss.config.menu.MenuIds;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.gloss.persistence.GlossProjectTransaction;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PanelCreationService {
  private static final long SHUTDOWN_TIMEOUT_SECONDS = 30L;
  private static final long FORCE_SHUTDOWN_TIMEOUT_SECONDS = 5L;

  private final Dependencies dependencies;
  private final ExecutorService executor;
  private final AtomicBoolean accepting;

  public PanelCreationService(Gloss plugin) {
    this(dependencies(Objects.requireNonNull(plugin, "plugin")),
        executor(plugin.getClass().getClassLoader()));
  }

  PanelCreationService(Dependencies dependencies, ExecutorService executor) {
    this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.accepting = new AtomicBoolean(true);
  }

  public void stopAccepting() {
    accepting.set(false);
  }

  public void shutdown() {
    stopAccepting();
    if (executor.isShutdown()) {
      return;
    }
    executor.shutdown();
    boolean interrupted = false;
    try {
      if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        dependencies.logger().warning(
            "Timed out draining hologram creation transactions; cancelling remaining work.");
        executor.shutdownNow();
        if (!executor.awaitTermination(FORCE_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          dependencies.logger().warning(
              "Hologram creation transactions did not stop after forced cancellation.");
        }
      }
    } catch (InterruptedException interruption) {
      interrupted = true;
      executor.shutdownNow();
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  public CompletableFuture<Creation> create(String id, String text, PanelTransform transform) {
    String hologramId;
    String menuSource;
    PanelDefinition board;
    try {
      hologramId = PanelIds.canonicalize(id);
      MenuIds.require(hologramId);
      menuSource = MenuBaselines.simpleHologramSource(hologramId, text);
      MenuDocumentParser.parse(hologramId, menuSource);
      board = PanelDefinition.create(hologramId, hologramId,
          Objects.requireNonNull(transform, "transform"));
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }

    CompletableFuture<Creation> result = new CompletableFuture<>();
    if (!accepting.get()) {
      result.completeExceptionally(new CancellationException(
          "hologram creation is unavailable until Gloss restarts"));
      return result;
    }
    try {
      executor.execute(() -> executeCreate(hologramId, menuSource, board, result));
    } catch (RejectedExecutionException rejection) {
      result.completeExceptionally(new CancellationException("hologram creation service is shut down"));
    }
    return result;
  }

  private void executeCreate(String id, String menuSource, PanelDefinition board,
                             CompletableFuture<Creation> result) {
    if (!accepting.get()) {
      result.completeExceptionally(new CancellationException("hologram creation service is shut down"));
      return;
    }

    GlossPersistenceCoordinator.ExternalTransaction lease = null;
    GlossProjectTransaction.Pending transaction = null;
    boolean transactionAttempted = false;
    MenuDocument expectedMenu = MenuDocumentParser.parse(id, menuSource);
    try {
      lease = dependencies.persistenceCoordinator().beginExternalTransaction();
      ensureAvailable(id);
      Map<Path, byte[]> expectedFiles = expectedFiles(id);
      transactionAttempted = true;
      transaction = dependencies.projectTransaction().apply(
          "create-" + id, Map.of(id, menuSource), Map.of(), board, expectedFiles);
      MenuDocument publishedMenu = dependencies.menus().publish(id, menuSource);
      PanelDefinition publishedBoard = dependencies.boards().publish(board);
      try {
        dependencies.projectTransaction().commit(transaction);
      } catch (GlossProjectTransaction.CommittedCleanupException cleanupFailure) {
        dependencies.logger().log(Level.WARNING,
            "Hologram creation committed, but transaction backup cleanup will retry at startup.",
            cleanupFailure);
      } catch (GlossProjectTransaction.CommitUncertainException uncertainFailure) {
        accepting.set(false);
        dependencies.persistenceCoordinator().requireRestartRecovery();
        dependencies.logger().log(Level.SEVERE,
            "Hologram creation durability is uncertain for '" + id
                + "'; do not retry creation until Gloss restarts.", uncertainFailure);
        result.completeExceptionally(new DurabilityUncertainException(id, uncertainFailure));
        return;
      }
      result.complete(new Creation(publishedMenu, publishedBoard));
    } catch (InterruptedException interruption) {
      Thread.currentThread().interrupt();
      Throwable reported = rollback(transactionAttempted, expectedMenu, board, interruption);
      if (reported instanceof DurabilityUncertainException) {
        result.completeExceptionally(reported);
      } else {
        CancellationException cancellation =
            new CancellationException("hologram creation was interrupted");
        cancellation.initCause(reported);
        result.completeExceptionally(cancellation);
      }
    } catch (Exception failure) {
      Throwable reported = rollback(transactionAttempted, expectedMenu, board, failure);
      result.completeExceptionally(reported);
    } finally {
      if (lease != null) {
        lease.close();
      }
    }
  }

  private void ensureAvailable(String id) throws IOException {
    Path menuFile = menuFile(id);
    Path boardFile = boardFile(id);
    if (dependencies.menus().exists(id)
        || dependencies.boards().current(id).isPresent()
        || Files.exists(menuFile, LinkOption.NOFOLLOW_LINKS)
        || Files.exists(boardFile, LinkOption.NOFOLLOW_LINKS)) {
      throw new FileAlreadyExistsException(id);
    }
  }

  private Map<Path, byte[]> expectedFiles(String id) {
    Map<Path, byte[]> expected = new HashMap<>();
    expected.put(menuFile(id), null);
    expected.put(boardFile(id), null);
    return expected;
  }

  private Throwable rollback(boolean transactionAttempted, MenuDocument menu,
                             PanelDefinition board, Throwable originalFailure) {
    if (!transactionAttempted) {
      if (dependencies.persistenceCoordinator().recoveryRequired()) {
        accepting.set(false);
        return new DurabilityUncertainException(board.id(), originalFailure);
      }
      return originalFailure;
    }
    try {
      dependencies.projectTransaction().recover();
    } catch (IOException rollbackFailure) {
      originalFailure.addSuppressed(rollbackFailure);
      accepting.set(false);
      dependencies.persistenceCoordinator().requireRestartRecovery();
      DurabilityUncertainException uncertain =
          new DurabilityUncertainException(board.id(), originalFailure);
      dependencies.logger().log(Level.SEVERE,
          "Hologram creation rollback could not restore persistent files for '" + board.id() + "'.",
          uncertain);
      return uncertain;
    }

    Throwable recoveryFailure = null;
    try {
      dependencies.boards().recover(board);
    } catch (IOException | RuntimeException boardFailure) {
      recoveryFailure = boardFailure;
    }
    try {
      dependencies.menus().recover(menu);
    } catch (IOException | RuntimeException menuFailure) {
      if (recoveryFailure == null) {
        recoveryFailure = menuFailure;
      } else {
        recoveryFailure.addSuppressed(menuFailure);
      }
    }
    if (recoveryFailure != null) {
      originalFailure.addSuppressed(recoveryFailure);
      accepting.set(false);
      dependencies.persistenceCoordinator().requireRestartRecovery();
      DurabilityUncertainException uncertain =
          new DurabilityUncertainException(board.id(), originalFailure);
      dependencies.logger().log(Level.SEVERE,
          "Hologram creation rollback did not fully restore runtime state for '" + board.id() + "'.",
          uncertain);
      return uncertain;
    }
    return originalFailure;
  }

  private Path menuFile(String id) {
    return confined(dependencies.dataDirectory().resolve("menus"), id + ".json");
  }

  private Path boardFile(String id) {
    return confined(dependencies.dataDirectory().resolve(PanelRepository.DIRECTORY_NAME),
        id + ".json");
  }

  private static Path confined(Path root, String relative) {
    Path normalizedRoot = root.toAbsolutePath().normalize();
    Path target = normalizedRoot.resolve(relative).normalize();
    if (target.equals(normalizedRoot) || !target.startsWith(normalizedRoot)) {
      throw new IllegalArgumentException("hologram id escapes persistent storage");
    }
    return target;
  }

  private static ExecutorService executor(ClassLoader classLoader) {
    ClassLoader requiredClassLoader = Objects.requireNonNull(classLoader, "classLoader");
    AtomicInteger sequence = new AtomicInteger();
    return Executors.newSingleThreadExecutor(task -> {
      Thread thread = new Thread(task, "Gloss-Panel-Creation-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      thread.setContextClassLoader(requiredClassLoader);
      return thread;
    });
  }

  private static Dependencies dependencies(Gloss plugin) {
    return new Dependencies(
        plugin.getDataFolder().toPath(),
        plugin.getLogger(),
        plugin.getPersistenceCoordinator(),
        plugin.getProjectTransaction(),
        new PluginMenuPublication(plugin),
        new PluginBoardPublication(plugin)
    );
  }

  public record Creation(MenuDocument menu, PanelDefinition board) {
    public Creation {
      menu = Objects.requireNonNull(menu, "menu");
      board = Objects.requireNonNull(board, "board");
    }
  }

  public static final class DurabilityUncertainException extends IOException {
    private DurabilityUncertainException(String id, Throwable cause) {
      super("hologram transaction for '" + id
              + "' could not be safely finalized; restart before inspecting or retrying",
          cause);
    }
  }

  record Dependencies(Path dataDirectory, Logger logger,
                      GlossPersistenceCoordinator persistenceCoordinator,
                      GlossProjectTransaction projectTransaction,
                      MenuPublication menus, PanelPublication boards) {
    Dependencies {
      dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
          .toAbsolutePath().normalize();
      logger = Objects.requireNonNull(logger, "logger");
      persistenceCoordinator = Objects.requireNonNull(
          persistenceCoordinator, "persistenceCoordinator");
      projectTransaction = Objects.requireNonNull(projectTransaction, "projectTransaction");
      menus = Objects.requireNonNull(menus, "menus");
      boards = Objects.requireNonNull(boards, "boards");
    }
  }

  interface MenuPublication {
    boolean exists(String id);

    MenuDocument publish(String id, String source) throws IOException;

    MenuDocument recover(MenuDocument created) throws IOException;
  }

  interface PanelPublication {
    Optional<PanelDefinition> current(String id);

    PanelDefinition publish(PanelDefinition created) throws IOException;

    PanelDefinition recover(PanelDefinition created) throws IOException;
  }

  private static final class PluginMenuPublication implements MenuPublication {
    private final Gloss plugin;

    private PluginMenuPublication(Gloss plugin) {
      this.plugin = plugin;
    }

    @Override
    public boolean exists(String id) {
      return plugin.getMenuCatalog().exists(id);
    }

    @Override
    public MenuDocument publish(String id, String source) throws IOException {
      return plugin.getMenuCatalog().publishExternalCreate(id, source);
    }

    @Override
    public MenuDocument recover(MenuDocument created) throws IOException {
      return plugin.getMenuCatalog().recoverExternalCreate(created);
    }
  }

  private static final class PluginBoardPublication implements PanelPublication {
    private final Gloss plugin;

    private PluginBoardPublication(Gloss plugin) {
      this.plugin = plugin;
    }

    @Override
    public Optional<PanelDefinition> current(String id) {
      return plugin.getPanelService().get(id);
    }

    @Override
    public PanelDefinition publish(PanelDefinition created) throws IOException {
      return plugin.getPanelService().publishExternalCreate(created);
    }

    @Override
    public PanelDefinition recover(PanelDefinition created) throws IOException {
      return plugin.getPanelService().recoverExternalCreate(created);
    }
  }
}
