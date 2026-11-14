package art.arcane.gloss.editor.sync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EditorWorkspacePublishTest {
  private static final int LIMIT = 64 * 1024 * 1024;

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void aPublishAnswersForItsOwnKeysAndHandsBackWhatElseChanged() throws IOException {
    Path data = folder.newFolder("data").toPath();
    EditorWorkspaceFixtures.hologram(data, "welcome", "Welcome");
    EditorWorkspaceFixtures.hologram(data, "spawn", "Spawn");
    EditorWorkspace workspace = EditorWorkspaceFixtures.detached(data, LIMIT);
    workspace.refresh();
    String welcome = workspace.view().row("hologram", "welcome").orElseThrow().hash();
    Files.writeString(data.resolve("holograms/spawn.json"), EditorWorkspaceFixtures.hologramJson(2L, "In game"));

    EditorWorkspace.Outcome outcome = workspace.publish("chg_own", put("welcome", welcome, "Published"), Map.of());

    assertEquals("applied", outcome.status());
    assertEquals(List.of("welcome"), ids(outcome.delta()));
    assertEquals(List.of("spawn"), ids(outcome.others()));
  }

  @Test
  public void aKeyThatKeptTheServerCopyIsHandedBackAsTheServersChange() throws IOException {
    Path data = folder.newFolder("data").toPath();
    EditorWorkspaceFixtures.hologram(data, "spawn", "Spawn");
    EditorWorkspaceFixtures.hologram(data, "welcome", "Welcome");
    EditorWorkspace workspace = EditorWorkspaceFixtures.detached(data, LIMIT);
    workspace.refresh();
    String spawn = workspace.view().row("hologram", "spawn").orElseThrow().hash();
    String welcome = workspace.view().row("hologram", "welcome").orElseThrow().hash();
    Files.writeString(data.resolve("holograms/spawn.json"), EditorWorkspaceFixtures.hologramJson(2L, "In game"));

    EditorWorkspace.Outcome outcome = workspace.publish("chg_kept", new EditorSyncChangeset(List.of(
        new EditorSyncChangeset.DocumentChange(false, "hologram", "spawn", spawn,
            EditorWorkspaceFixtures.hologramJson(1L, "Mine")),
        new EditorSyncChangeset.DocumentChange(false, "hologram", "welcome", welcome,
            EditorWorkspaceFixtures.hologramJson(1L, "Published"))), List.of()), Map.of());

    assertEquals(1, outcome.conflicts().size());
    assertEquals(List.of("welcome"), ids(outcome.delta()));
    assertEquals(List.of("spawn"), ids(outcome.others()));
  }

  @Test
  public void anUnreadableDocumentElsewhereDoesNotStopAPublish() throws IOException {
    Path data = folder.newFolder("data").toPath();
    EditorWorkspaceFixtures.hologram(data, "welcome", "Welcome");
    Files.writeString(data.resolve("holograms/broken.json"), "{\"schemaVersion\":");
    EditorWorkspace workspace = EditorWorkspaceFixtures.detached(data, LIMIT);
    workspace.refresh();
    String welcome = workspace.view().row("hologram", "welcome").orElseThrow().hash();

    EditorWorkspace.Outcome outcome = workspace.publish("chg_broken", put("welcome", welcome, "Published"), Map.of());

    assertEquals(outcome.message(), "applied", outcome.status());
    assertTrue(Files.readString(data.resolve("holograms/welcome.json")).contains("Published"));
    assertEquals("{\"schemaVersion\":", Files.readString(data.resolve("holograms/broken.json")));
  }

  @Test
  public void aDocumentThatTurnsUnreadableIsNotReportedAsDeleted() throws IOException {
    Path data = folder.newFolder("data").toPath();
    EditorWorkspaceFixtures.hologram(data, "spawn", "Spawn");
    EditorWorkspace workspace = EditorWorkspaceFixtures.detached(data, LIMIT);
    workspace.refresh();
    String before = workspace.view().row("hologram", "spawn").orElseThrow().hash();
    Files.writeString(data.resolve("holograms/spawn.json"), "{\"schemaVersion\":");

    EditorWorkspace.Delta unreadable = workspace.refresh();
    String kept = workspace.view().row("hologram", "spawn").orElseThrow().hash();
    Files.writeString(data.resolve("holograms/spawn.json"), EditorWorkspaceFixtures.hologramJson(2L, "Fixed"));
    EditorWorkspace.Delta readable = workspace.refresh();

    assertTrue(unreadable.deleted().isEmpty());
    assertEquals(before, kept);
    assertEquals(List.of("spawn"), ids(readable));
  }

  @Test
  public void aPublishToAnUnreadableDocumentKeepsTheFileAndNamesItAsAConflict() throws IOException {
    Path data = folder.newFolder("data").toPath();
    EditorWorkspaceFixtures.hologram(data, "spawn", "Spawn");
    EditorWorkspaceFixtures.hologram(data, "welcome", "Welcome");
    EditorWorkspace workspace = EditorWorkspaceFixtures.detached(data, LIMIT);
    workspace.refresh();
    String spawn = workspace.view().row("hologram", "spawn").orElseThrow().hash();
    String welcome = workspace.view().row("hologram", "welcome").orElseThrow().hash();
    Files.writeString(data.resolve("holograms/spawn.json"), "{\"schemaVersion\":");

    EditorWorkspace.Outcome outcome = workspace.publish("chg_mixed", new EditorSyncChangeset(List.of(
        new EditorSyncChangeset.DocumentChange(false, "hologram", "spawn", spawn,
            EditorWorkspaceFixtures.hologramJson(1L, "Mine")),
        new EditorSyncChangeset.DocumentChange(false, "hologram", "welcome", welcome,
            EditorWorkspaceFixtures.hologramJson(1L, "Published"))), List.of()), Map.of());

    assertEquals(outcome.message(), "applied", outcome.status());
    assertEquals(1, outcome.conflicts().size());
    assertEquals("spawn", outcome.conflicts().getFirst().id());
    assertEquals("{\"schemaVersion\":", Files.readString(data.resolve("holograms/spawn.json")));
    assertTrue(Files.readString(data.resolve("holograms/welcome.json")).contains("Published"));
  }

  @Test
  public void deletingACustomizedShippedDefaultResetsItToTheStockCopy() throws IOException {
    Path data = folder.newFolder("data").toPath();
    Path board = data.resolve("boards/default.json");
    Files.createDirectories(board.getParent());
    JsonObject custom = stock("boards/default.json");
    custom.addProperty("revision", 4L);
    custom.getAsJsonObject("presentation").addProperty("title", "&dCustom default");
    Files.writeString(board, custom.toString());
    EditorWorkspace workspace = EditorWorkspaceFixtures.detached(data, LIMIT);
    workspace.refresh();
    String base = workspace.view().row("scoreboard", "default").orElseThrow().hash();

    EditorWorkspace.Outcome outcome = workspace.publish("chg_reset", new EditorSyncChangeset(List.of(
        new EditorSyncChangeset.DocumentChange(true, "scoreboard", "default", base, null)), List.of()), Map.of());

    assertEquals(outcome.message(), "applied", outcome.status());
    assertTrue(outcome.message(), outcome.message().contains("scoreboard default"));
    assertTrue(outcome.delta().deleted().isEmpty());
    assertEquals(List.of("default"), ids(outcome.delta()));
    JsonObject written = JsonParser.parseString(Files.readString(board)).getAsJsonObject();
    assertEquals(5L, written.get("revision").getAsLong());
    written.remove("revision");
    JsonObject expected = stock("boards/default.json");
    expected.remove("revision");
    assertEquals(expected, written);
  }

  private static JsonObject stock(String resource) throws IOException {
    try (InputStream stream = EditorWorkspacePublishTest.class.getResourceAsStream("/defaults/" + resource)) {
      return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }
  }

  private static EditorSyncChangeset put(String id, String base, String line) {
    return new EditorSyncChangeset(List.of(new EditorSyncChangeset.DocumentChange(false, "hologram", id, base,
        EditorWorkspaceFixtures.hologramJson(1L, line))), List.of());
  }

  private static List<String> ids(EditorWorkspace.Delta delta) {
    List<String> ids = new ArrayList<>();
    for (EditorWorkspace.DocumentRow row : delta.documents()) {
      ids.add(row.id());
    }
    return ids;
  }
}
