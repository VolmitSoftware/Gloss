package art.arcane.gloss.importer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExportTest {
  private static final String BOARD = "{\"schemaVersion\":2,\"revision\":1,\"show\":true}";
  private static final String MENU = "{\"components\":[]}";

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void everyDocumentIsCopiedAsPrettyJsonBesideAnIndex() throws Exception {
    Path data = workspace();
    Path destination = temp.newFolder("export").toPath();

    ExportService.Result result = new ExportService(data).export(destination,
        Optional.empty(), Optional.empty());

    assertEquals(3, result.documents());
    assertTrue(Files.readString(destination.resolve("boards/lobby.json")).contains("\n  \""));
    assertTrue(Files.exists(destination.resolve("menus/shop/main.json")));
    JsonObject index = JsonParser.parseString(
        Files.readString(destination.resolve("workspace.json"))).getAsJsonObject();
    assertEquals("gloss-workspace-export", index.get("format").getAsString());
    assertEquals(3, index.getAsJsonArray("documents").size());
  }

  @Test
  public void narrowingToOneKindAndIdWritesOnlyThatDocument() throws Exception {
    Path data = workspace();
    Path destination = temp.newFolder("narrow").toPath();

    ExportService.Result result = new ExportService(data).export(destination,
        Optional.of("boards"), Optional.of("lobby"));

    assertEquals(1, result.documents());
    assertTrue(Files.notExists(destination.resolve("tablist")));
  }

  @Test
  public void theBundleExportUsesTheEditorWorkspaceShape() throws Exception {
    Path data = workspace();
    Path destination = temp.newFolder("bundle").toPath();

    new ExportService(data).exportBundle(destination);

    JsonObject bundle = JsonParser.parseString(
        Files.readString(destination.resolve("workspace-bundle.json"))).getAsJsonObject();
    assertEquals("gloss-editor-workspace", bundle.get("format").getAsString());
    assertEquals(1, bundle.get("version").getAsInt());
    JsonObject workspace = bundle.getAsJsonObject("workspace");
    assertEquals(2, workspace.get("schemaVersion").getAsInt());
    assertEquals(3, workspace.getAsJsonArray("documents").size());
    JsonObject document = workspace.getAsJsonArray("documents").get(0).getAsJsonObject();
    assertTrue(document.toString(), document.get("id").getAsString()
        .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
    assertTrue(document.get("kind").getAsString(),
        java.util.Set.of("scoreboard", "menu", "tablist")
            .contains(document.get("kind").getAsString()));
  }

  private Path workspace() throws Exception {
    Path data = temp.newFolder("data-" + System.nanoTime()).toPath();
    write(data.resolve("boards/lobby.json"), BOARD);
    write(data.resolve("menus/shop/main.json"), MENU);
    write(data.resolve("tablist.json"), BOARD);
    return data;
  }

  private static void write(Path path, String source) throws Exception {
    Files.createDirectories(path.getParent());
    Files.writeString(path, source, StandardCharsets.UTF_8);
  }
}
