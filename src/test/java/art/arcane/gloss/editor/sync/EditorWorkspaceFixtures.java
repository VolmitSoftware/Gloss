package art.arcane.gloss.editor.sync;

import art.arcane.gloss.persistence.GlossProjectTransaction;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Workspaces over a temporary data folder for tests: publishes write the files directly, without a server. */
public final class EditorWorkspaceFixtures {
  private EditorWorkspaceFixtures() {
  }

  /** Holds note building once armed: the build signals {@code entered} and waits for {@code release}. */
  public static final class NotesGate {
    public final CountDownLatch entered = new CountDownLatch(1);
    public final CountDownLatch release = new CountDownLatch(1);
    public volatile boolean armed;

    void pass() {
      if (!armed) {
        return;
      }
      entered.countDown();
      try {
        release.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public static EditorWorkspace detached(Path data, int maximumBytes) {
    return detached(data, maximumBytes, new NotesGate());
  }

  public static EditorWorkspace detached(Path data, int maximumBytes, NotesGate gate) {
    EditorSyncContentSnapshotBuilder content = new EditorSyncContentSnapshotBuilder(data);
    EditorSyncContentSnapshotBuilder.ServerSections sections = new EditorSyncContentSnapshotBuilder.ServerSections() {
      @Override
      public JsonArray history(List<EditorSyncDocuments.Entry> documents) {
        gate.pass();
        JsonArray history = new JsonArray();
        for (EditorSyncDocuments.Entry document : documents) {
          if (document.kind().equals("hologram")) {
            history.add(EditorSyncProjectSections.historyEntry("holograms", document.id(), 1700L, "watchdog", 12L));
          }
        }
        return history;
      }

      @Override
      public JsonObject schemas() {
        JsonObject schemas = new JsonObject();
        schemas.add("hologram", new JsonObject());
        return schemas;
      }
    };
    EditorWorkspace.Applier applier = (String changeId, EditorSyncProject server,
                                       EditorSyncPublicationValidator.ValidatedProject validated,
                                       java.util.function.Supplier<EditorSyncProject> reread) -> write(data, server, validated);
    return new EditorWorkspace(content, sections, applier, (String collection, String id, long version) ->
        version == 1700L ? Optional.of("{\"stored\":true}") : Optional.empty(), () -> maximumBytes);
  }

  /** Writes the bytes the server's apply path would write, straight to disk. */
  private static void write(Path data, EditorSyncProject server,
                            EditorSyncPublicationValidator.ValidatedProject validated) throws IOException {
    Path root = data.toAbsolutePath().normalize();
    Map<Path, byte[]> expected = EditorSyncApplier.expectedFiles(root, server.json(), validated);
    for (Map.Entry<Path, GlossProjectTransaction.Mutation> mutation
        : EditorSyncApplier.mutations(root, validated, expected).entrySet()) {
      Path target = mutation.getKey();
      if (mutation.getValue().operation() == GlossProjectTransaction.Operation.DELETE) {
        Files.deleteIfExists(target);
      } else {
        Files.createDirectories(target.getParent());
        Files.write(target, mutation.getValue().content());
      }
    }
  }

  /** Writes a hologram document with this text as its first line. */
  public static void hologram(Path data, String id, String line) throws IOException {
    Path file = data.resolve("holograms").resolve(id + ".json");
    Files.createDirectories(file.getParent());
    Files.writeString(file, hologramJson(1L, line), StandardCharsets.UTF_8);
  }

  /** A valid hologram body with this revision and first line. */
  public static String hologramJson(long revision, String line) {
    return "{\"schemaVersion\":3,\"revision\":" + revision + ",\"anchor\":{\"world\":\"world\",\"position\":[0.0,0.0,0.0]},"
        + "\"lines\":[\"" + line + "\"],\"particleLayers\":[],\"style\":{\"billboard\":\"center\",\"seeThrough\":true,"
        + "\"scaleX\":1.0,\"scaleY\":1.0,\"scaleZ\":1.0},\"box\":{\"enabled\":false,\"padding\":4,\"borderWidth\":1,"
        + "\"backgroundArgb\":\"#B31B1B22\",\"borderArgb\":\"#FFAAAAAA\"}}\n";
  }

  /** Writes a menu document. */
  public static void menu(Path data, String id, String json) throws IOException {
    Path file = data.resolve("menus").resolve(id + ".json");
    Files.createDirectories(file.getParent());
    Files.writeString(file, json, StandardCharsets.UTF_8);
  }

  public static byte[] png(int seed) {
    return EditorSyncTestProjects.png(seed);
  }
}
