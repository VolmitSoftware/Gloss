package art.arcane.gloss.editor.sync;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentPresetCatalog;
import art.arcane.gloss.history.HistoryKinds;
import art.arcane.gloss.history.HistoryService;
import art.arcane.gloss.panel.PanelDefinition;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.gloss.persistence.GlossProjectTransaction;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Writes a validated publish to the data folder in one Gloss project transaction, publishes the touched kinds to
 * the running server, and commits; on any failure it restores the files and republishes what they held. Runs on
 * the editor's file lane and waits for the server thread's part.
 */
final class EditorSyncApplier implements EditorWorkspace.Applier {
  private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
  private static final long RUNTIME_SECONDS = 60L;

  private final Gloss plugin;

  EditorSyncApplier(Gloss plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  @Override
  public void apply(String changeId, EditorSyncProject server,
                    EditorSyncPublicationValidator.ValidatedProject validated,
                    Supplier<EditorSyncProject> reread) throws IOException {
    GlossPersistenceCoordinator.ExternalTransaction lease;
    try {
      lease = plugin.getPersistenceCoordinator().beginExternalTransaction();
    } catch (InterruptedException interruption) {
      Thread.currentThread().interrupt();
      throw new IOException("the publish was interrupted", interruption);
    }
    try {
      if (!EditorSyncJson.contentRevision(reread.get().json()).equals(EditorSyncJson.contentRevision(server.json()))) {
        throw new IOException("the server's files changed while the publish was prepared; publish again");
      }
      Path data = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
      Map<Path, byte[]> expectedFiles = expectedFiles(data, server.json(), validated);
      Map<Path, GlossProjectTransaction.Mutation> mutations = mutations(data, validated, expectedFiles);
      GlossProjectTransaction.Pending transaction = plugin.getProjectTransaction().apply(changeId, mutations,
          expectedFiles);
      try {
        await(publishRuntime(validated.appliedMenus(), validated.deletedMenus(), validated));
        String expected = EditorSyncJson.contentRevision(validated.project().json());
        if (!EditorSyncJson.contentRevision(reread.get().json()).equals(expected)) {
          throw new IOException("the server's files changed while the publish was applied");
        }
        plugin.getProjectTransaction().commit(transaction);
        keepDeleted(changeId, expectedFiles, mutations);
      } catch (GlossProjectTransaction.CommittedCleanupException cleanup) {
        Gloss.warn("Web editor publish committed; transaction backup cleanup will retry at startup.");
        keepDeleted(changeId, expectedFiles, mutations);
      } catch (GlossProjectTransaction.CommitUncertainException uncertain) {
        throw uncertain;
      } catch (IOException | RuntimeException failure) {
        boolean undone = rollback(server, validated, failure);
        IOException thrown = failure instanceof IOException io ? io : new IOException(failure.getMessage(), failure);
        if (!undone) {
          throw new EditorWorkspace.StrandedPublishException(thrown.getMessage(), thrown);
        }
        throw thrown;
      }
    } finally {
      lease.close();
    }
  }

  /** A document a publish deleted, with the bytes it held. */
  record Deleted(String collection, String id, byte[] bytes) {
  }

  /**
   * Stores a history copy of every document the publish deleted. The transaction backup holds one too, but only the
   * newest {@code maxTransactionBackups} publishes keep theirs; history keeps it as long as any other version.
   */
  private void keepDeleted(String changeId, Map<Path, byte[]> expectedFiles,
                           Map<Path, GlossProjectTransaction.Mutation> mutations) {
    HistoryService history = plugin.service(HistoryService.class);
    if (history == null) {
      return;
    }
    Path data = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
    for (Deleted deleted : deleted(data, expectedFiles, mutations)) {
      history.record(deleted.collection(), deleted.id(), deleted.bytes(), "editor:" + changeId);
    }
  }

  /** The documents among {@code mutations} that are deletions of a file that existed, with the bytes it held. */
  static List<Deleted> deleted(Path data, Map<Path, byte[]> expectedFiles,
                               Map<Path, GlossProjectTransaction.Mutation> mutations) {
    List<Deleted> deleted = new ArrayList<>();
    for (Map.Entry<Path, GlossProjectTransaction.Mutation> mutation : mutations.entrySet()) {
      byte[] bytes = expectedFiles.get(mutation.getKey());
      if (mutation.getValue().operation() != GlossProjectTransaction.Operation.DELETE || bytes == null
          || !mutation.getKey().startsWith(data)) {
        continue;
      }
      HistoryKinds.DocumentPath document = HistoryKinds.resolve(data, data.relativize(mutation.getKey()).toString());
      if (document != null) {
        deleted.add(new Deleted(document.collection(), document.id(), bytes));
      }
    }
    return List.copyOf(deleted);
  }

  /** Restores the files and the runtime; false when the files could not be restored and were left as found. */
  private boolean rollback(EditorSyncProject server, EditorSyncPublicationValidator.ValidatedProject validated,
                           Exception failure) {
    try {
      if (!plugin.getProjectTransaction().recover().isEmpty()) {
        return false;
      }
    } catch (IOException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
      return false;
    }
    Map<String, String> baseMenus = Map.copyOf(EditorSyncDocuments.menuSources(EditorSyncDocuments.parse(server.json())));
    Set<String> rolledBackMenus = new TreeSet<>(validated.appliedMenus().keySet());
    rolledBackMenus.removeAll(baseMenus.keySet());
    try {
      await(publishRuntime(baseMenus, rolledBackMenus, validated));
    } catch (IOException republishFailure) {
      failure.addSuppressed(republishFailure);
    }
    return true;
  }

  private CompletableFuture<Void> publishRuntime(Map<String, String> menus, Set<String> deletedMenus,
                                                 EditorSyncPublicationValidator.ValidatedProject validated) {
    CompletableFuture<?> menuPublication = validated.changedKinds().contains(EditorSyncDocumentKind.MENU)
        ? plugin.getMenuCatalog().publishEditorSyncProject(menus, deletedMenus)
        : CompletableFuture.completedFuture(null);
    return menuPublication.thenCompose((Object ignored) ->
        plugin.publishEditorSyncRuntime(validated.changedKinds(), validated.imagesChanged()));
  }

  private static void await(CompletableFuture<?> future) throws IOException {
    try {
      future.get(RUNTIME_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException interruption) {
      Thread.currentThread().interrupt();
      throw new IOException("the publish was interrupted", interruption);
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause() == null ? failure : failure.getCause();
      throw new IOException("the server could not load the change: " + cause.getMessage(), cause);
    } catch (TimeoutException slow) {
      throw new IOException("the server did not load the change within " + RUNTIME_SECONDS + " s", slow);
    }
  }

  /** The bytes each file the publish touches holds now, null for a file it creates. */
  static Map<Path, byte[]> expectedFiles(Path data, JsonObject serverProject,
                                         EditorSyncPublicationValidator.ValidatedProject publication) throws IOException {
    Map<Path, byte[]> expected = new LinkedHashMap<>();
    Set<EditorSyncPublicationValidator.DocumentKey> documentKeys = new HashSet<>(publication.baseDocuments().keySet());
    documentKeys.addAll(publication.appliedDocuments().keySet());
    for (EditorSyncPublicationValidator.DocumentKey key : documentKeys) {
      EditorSyncPublicationValidator.ParsedEntry base = publication.baseDocuments().get(key);
      EditorSyncPublicationValidator.ParsedEntry applied = publication.appliedDocuments().get(key);
      if (sameEntry(base, applied)) {
        continue;
      }
      Path target = key.kind().path(data, key.id());
      byte[] expectedBytes;
      if (base == null) {
        expectedBytes = null;
      } else if (key.kind() == EditorSyncDocumentKind.PANEL) {
        expectedBytes = expectedBoardFile(target, serverProject, key.id());
      } else {
        expectedBytes = base.entry().json().getBytes(StandardCharsets.UTF_8);
      }
      expected.put(target, expectedBytes);
    }
    Set<String> imagePaths = new HashSet<>(publication.baseImages().keySet());
    imagePaths.addAll(publication.appliedImages().keySet());
    for (String path : imagePaths) {
      byte[] base = publication.baseImages().get(path);
      byte[] applied = publication.appliedImages().get(path);
      if (Arrays.equals(base, applied)) {
        continue;
      }
      expected.put(data.resolve("images").resolve(path).normalize(), base);
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(expected));
  }

  /** The writes and deletes for a publish; {@code expectedFiles} holds what each touched file holds now. */
  static Map<Path, GlossProjectTransaction.Mutation> mutations(Path data,
      EditorSyncPublicationValidator.ValidatedProject publication, Map<Path, byte[]> expectedFiles) {
    DocumentPresetCatalog presets = EditorSyncPublicationValidator.presets(publication.project().json());
    Map<Path, GlossProjectTransaction.Mutation> mutations = new LinkedHashMap<>();
    Set<EditorSyncPublicationValidator.DocumentKey> documentKeys = new HashSet<>(publication.baseDocuments().keySet());
    documentKeys.addAll(publication.appliedDocuments().keySet());
    for (EditorSyncPublicationValidator.DocumentKey key : documentKeys) {
      EditorSyncPublicationValidator.ParsedEntry base = publication.baseDocuments().get(key);
      EditorSyncPublicationValidator.ParsedEntry applied = publication.appliedDocuments().get(key);
      if (sameEntry(base, applied)) {
        continue;
      }
      Path path = key.kind().path(data, key.id());
      mutations.put(path, applied == null ? GlossProjectTransaction.Mutation.delete()
          : GlossProjectTransaction.Mutation.write(key.kind().persistedBytes(key.id(), applied.entry().json(), presets,
              expectedFiles.get(path))));
    }
    Set<String> imagePaths = new HashSet<>(publication.baseImages().keySet());
    imagePaths.addAll(publication.appliedImages().keySet());
    for (String path : imagePaths) {
      byte[] base = publication.baseImages().get(path);
      byte[] applied = publication.appliedImages().get(path);
      if (Arrays.equals(base, applied)) {
        continue;
      }
      Path target = data.resolve("images").resolve(path).normalize();
      mutations.put(target, applied == null ? GlossProjectTransaction.Mutation.delete()
          : GlossProjectTransaction.Mutation.write(applied));
    }
    return Map.copyOf(mutations);
  }

  private static boolean sameEntry(EditorSyncPublicationValidator.ParsedEntry left,
                                   EditorSyncPublicationValidator.ParsedEntry right) {
    return left == null ? right == null : right != null && left.entry().json().equals(right.entry().json());
  }

  /** The panel file as it is on disk, after checking it still parses to the panel the project captured. */
  static byte[] expectedBoardFile(Path boardPath, JsonObject baseProject, String id) throws IOException {
    if (Files.isSymbolicLink(boardPath) || !Files.isRegularFile(boardPath, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("board snapshot file must be a regular non-symbolic file");
    }
    byte[] source = Files.readAllBytes(boardPath);
    PanelDefinition persisted;
    PanelDefinition captured;
    try {
      JsonElement parsed = JsonParser.parseString(new String(source, StandardCharsets.UTF_8));
      if (!parsed.isJsonObject()) {
        throw new IllegalArgumentException("board document must be an object");
      }
      persisted = GSON.fromJson(parsed, PanelDefinition.class);
      List<EditorSyncDocuments.Entry> panels = EditorSyncDocuments.ofKind(EditorSyncDocuments.parse(baseProject),
          EditorSyncDocumentKind.PANEL.wireName()).stream()
          .filter((EditorSyncDocuments.Entry entry) -> entry.id().equals(id)).toList();
      if (panels.size() != 1) {
        throw new IllegalArgumentException("the workspace is missing panel " + id);
      }
      captured = GSON.fromJson(JsonParser.parseString(panels.getFirst().json()), PanelDefinition.class);
    } catch (RuntimeException failure) {
      throw new IOException("board snapshot file is invalid", failure);
    }
    if (persisted == null || captured == null || !persisted.equals(captured)) {
      throw new IOException("board file " + id + " changed while the publish was prepared");
    }
    return source;
  }
}
