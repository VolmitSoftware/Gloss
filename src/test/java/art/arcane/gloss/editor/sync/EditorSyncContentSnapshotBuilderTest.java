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
        .open(1024 * 1024);

    assertEquals("workspace", project.json().get("kind").getAsString());
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
  public void aMalformedDocumentRefusesTheWholeProjectButOnlyWarnsInTheListing() throws Exception {
    Path data = temp.newFolder("malformed-isolation").toPath();
    write(data.resolve("menus/shop.json"), "{\"components\":[]}");
    write(data.resolve("holograms/broken.json"), "not json");
    EditorSyncContentSnapshotBuilder builder = new EditorSyncContentSnapshotBuilder(data);

    EditorSyncContentSnapshotBuilder.Listing listing = builder.list(true, 1024 * 1024);

    assertEquals(1, listing.documents().size());
    assertEquals("shop", listing.documents().getFirst().id());
    assertTrue(listing.warnings().stream().anyMatch(warning -> warning.startsWith("unreadable|hologram|broken||")));
    assertThrows(RuntimeException.class, () -> builder.open(1024 * 1024));
  }

  @Test
  public void aListingReadsAgainOnlyTheFilesThatChanged() throws Exception {
    Path data = temp.newFolder("cached-listing").toPath();
    write(data.resolve("menus/shop.json"), "{\"components\":[]}");
    write(data.resolve("menus/other.json"), "{\"components\":[]}");
    EditorSyncContentSnapshotBuilder builder = new EditorSyncContentSnapshotBuilder(data);
    EditorSyncDocuments.Entry before = builder.list(true, 1024 * 1024).documents().getFirst();

    write(data.resolve("menus/shop.json"), "{\"offset\":[0,0,1],\"components\":[]}");
    Files.setLastModifiedTime(data.resolve("menus/shop.json"),
        java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000L));
    List<EditorSyncDocuments.Entry> after = builder.list(true, 1024 * 1024).documents();

    assertTrue(before == after.getFirst());
    assertEquals("{\"offset\":[0,0,1],\"components\":[]}", after.get(1).json());
  }

  @Test
  public void unsupportedSchemasAreAbsentFromSubjectsAndWorkspaceSnapshots() throws Exception {
    Path data = temp.newFolder("unsupported-schemas").toPath();
    write(data.resolve("animations/old.json"), "{\"schemaVersion\":2,\"revision\":1}");
    write(data.resolve("tablist.json"), "{\"schemaVersion\":1,\"revision\":1}");
    EditorSyncContentSnapshotBuilder builder = new EditorSyncContentSnapshotBuilder(data);

    assertTrue(builder.open(1024 * 1024)
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

    EditorSyncProject workspace = builder.open(1024 * 1024);

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
        .open(1024 * 1024);
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
