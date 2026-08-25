package art.arcane.gloss.editor.sync;

import art.arcane.gloss.panel.PanelDefinition;
import art.arcane.gloss.panel.PanelTransform;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class EditorSyncContentSnapshotBuilderTest {
  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void emptyWorkspaceIsACanonicalValidProject() throws Exception {
    Path data = temp.newFolder("empty-workspace").toPath();

    EditorSyncProject project = new EditorSyncContentSnapshotBuilder(data)
        .open(EditorSyncKind.WORKSPACE, "workspace", 1024 * 1024);

    assertEquals(EditorSyncKind.WORKSPACE, project.kind());
    assertTrue(project.json().getAsJsonArray("documents").isEmpty());
    assertTrue(project.json().getAsJsonArray("images").isEmpty());
    JsonObject constraints = project.json().getAsJsonObject("constraints");
    assertEquals(Set.of("subjectId", "documentKinds", "createDocumentKinds", "allowDeletes"),
        constraints.keySet());
    assertEquals(EditorSyncDocumentKind.ORDERED_WIRE_NAMES,
        strings(constraints.getAsJsonArray("documentKinds")));
    assertEquals(EditorSyncDocumentKind.ORDERED_WIRE_NAMES,
        strings(constraints.getAsJsonArray("createDocumentKinds")));
    assertTrue(constraints.get("allowDeletes").getAsBoolean());
  }

  @Test
  public void focusedMenuOpenAndSubjectIdsIgnoreMalformedUnrelatedKinds() throws Exception {
    Path data = temp.newFolder("focused-isolation").toPath();
    write(data.resolve("menus/shop.json"), "{\"components\":[]}");
    write(data.resolve("holograms/broken.json"), "not json");
    EditorSyncContentSnapshotBuilder builder = new EditorSyncContentSnapshotBuilder(data);

    EditorSyncProject menu = builder.open(EditorSyncKind.MENU, "shop", 1024 * 1024);

    assertEquals(List.of("shop"), builder.subjectIds(EditorSyncKind.MENU));
    assertEquals(1, menu.json().getAsJsonArray("documents").size());
    assertThrows(RuntimeException.class,
        () -> builder.open(EditorSyncKind.WORKSPACE, "workspace", 1024 * 1024));
  }

  @Test
  public void unsupportedSchemasAreAbsentFromSubjectsAndWorkspaceSnapshots() throws Exception {
    Path data = temp.newFolder("unsupported-schemas").toPath();
    write(data.resolve("animations/old.json"), "{\"schemaVersion\":2,\"revision\":1}");
    write(data.resolve("tablist.json"), "{\"schemaVersion\":1,\"revision\":1}");
    EditorSyncContentSnapshotBuilder builder = new EditorSyncContentSnapshotBuilder(data);

    assertTrue(builder.subjectIds(EditorSyncKind.ANIMATION).isEmpty());
    assertTrue(builder.subjectIds(EditorSyncKind.TABLIST).isEmpty());
    assertTrue(builder.open(EditorSyncKind.WORKSPACE, "workspace", 1024 * 1024)
        .json().getAsJsonArray("documents").isEmpty());
  }

  @Test
  public void workspaceRoundTripsUnreferencedImagesAtGeneralDimensions() throws Exception {
    Path data = temp.newFolder("workspace-images").toPath();
    Path image = data.resolve("images/archive/unreferenced.png");
    Files.createDirectories(image.getParent());
    BufferedImage source = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
    ImageIO.write(source, "png", image.toFile());
    EditorSyncContentSnapshotBuilder builder = new EditorSyncContentSnapshotBuilder(data);

    EditorSyncProject workspace = builder.open(
        EditorSyncKind.WORKSPACE, "workspace", 1024 * 1024);

    assertEquals("archive/unreferenced.png",
        workspace.json().getAsJsonArray("images").get(0).getAsJsonObject()
            .get("path").getAsString());
  }

  @Test
  public void panelSnapshotsPublishCanonicalWireJsonFromPrettyDiskFiles() throws Exception {
    Path data = temp.newFolder("panel-canonical").toPath();
    write(data.resolve("menus/root.json"), "{\"components\":[]}");
    PanelDefinition panel = PanelDefinition.create("spawn", "root",
        PanelTransform.at("minecraft:overworld", UUID.randomUUID(),
            1.0D, 64.0D, 2.0D, 0.0D));
    write(data.resolve("panels/spawn.json"),
        new GsonBuilder().serializeNulls().setPrettyPrinting().create()
            .toJson(panel));

    EditorSyncProject project = new EditorSyncContentSnapshotBuilder(data)
        .open(EditorSyncKind.PANEL, "spawn", 1024 * 1024);
    EditorSyncDocuments.Entry panelEntry = EditorSyncDocuments.parse(project.json()).stream()
        .filter(entry -> entry.kind().equals("panel"))
        .findFirst()
        .orElseThrow();

    assertEquals(EditorSyncJson.canonical(BukkitJson.GSON.toJsonTree(panel)), panelEntry.json());
    assertEquals(Long.valueOf(panel.revision()), panelEntry.revision());
  }

  private static List<String> strings(JsonArray values) {
    return values.asList().stream().map(value -> value.getAsString()).toList();
  }

  private static void write(Path path, String source) throws Exception {
    Files.createDirectories(path.getParent());
    Files.writeString(path, source, StandardCharsets.UTF_8);
  }
}
