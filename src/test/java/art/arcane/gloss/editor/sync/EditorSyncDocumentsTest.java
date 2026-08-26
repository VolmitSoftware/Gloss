package art.arcane.gloss.editor.sync;

import art.arcane.gloss.indicator.DamageIndicatorSettingsDoc;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class EditorSyncDocumentsTest {
  @Test
  public void unsupportedProjectVersionIsRejected() {
    JsonObject project = EditorSyncTestProjects.menuProject("fixture", "{\"components\":[]}");
    project.addProperty("version", EditorSyncJson.PROTOCOL_VERSION + 1);
    EditorSyncTestProjects.sign(project);

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> EditorSyncProject.validated(project, 1024 * 1024));

    assertTrue(failure.getMessage(),
        failure.getMessage().contains("unsupported sync project version"));
  }

  @Test
  public void unsupportedDocumentKindsAreRejectedByName() {
    JsonObject project = EditorSyncTestProjects.menuProject("fixture", "{\"components\":[]}");
    JsonArray documents = new JsonArray();
    documents.add(EditorSyncTestProjects.document("unknown-kind", "fixture", "{}"));
    project.add("documents", documents);
    EditorSyncTestProjects.sign(project);

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> EditorSyncProject.validated(project, 1024 * 1024));

    assertTrue(failure.getMessage(), failure.getMessage().contains("unknown-kind"));
  }

  @Test
  public void allCanonicalDocumentKindsAreHandled() {
    for (EditorSyncDocumentKind kind : EditorSyncDocumentKind.ORDERED) {
      assertEquals(kind, EditorSyncDocumentKind.parseWireName(kind.wireName()));
    }
    assertEquals(12, EditorSyncDocumentKind.ORDERED.size());
  }

  @Test
  public void damageIndicatorsUseTheVersionedCanonicalSingletonContract() {
    EditorSyncDocumentKind kind = EditorSyncDocumentKind.DAMAGE_INDICATORS;
    Path dataDirectory = Path.of("build", "sync-contract");
    EditorSyncDocumentKind.ParsedDocument parsed = kind.parse("default", """
        {"schemaVersion":3,"revision":7,"damage":{},"healing":{}}
        """);

    assertEquals("damage-indicators", kind.wireName());
    assertEquals(dataDirectory.toAbsolutePath().normalize()
        .resolve("damage-indicators/default.json"), kind.path(dataDirectory, "default"));
    assertEquals(Long.valueOf(7L), parsed.revision());
    assertTrue(parsed.value() instanceof DamageIndicatorSettingsDoc);
    assertThrows(IllegalArgumentException.class, () -> kind.canonicalId("custom"));
  }

  @Test
  public void documentCollectionAllowsEmptyAndLimitsFiveHundredTwelveEntries() {
    JsonObject empty = new JsonObject();
    empty.add("documents", new JsonArray());
    assertTrue(EditorSyncDocuments.parse(empty).isEmpty());

    JsonObject project = EditorSyncTestProjects.menuProject("fixture", "{\"components\":[]}");
    JsonArray documents = new JsonArray();
    for (int index = 0; index < EditorSyncDocuments.MAX_DOCUMENTS + 1; index++) {
      documents.add(EditorSyncTestProjects.document("menu",
          String.format("menu-%03d", index), "{\"components\":[]}"));
    }
    project.add("documents", documents);
    EditorSyncTestProjects.sign(project);

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> EditorSyncProject.validated(project, 32 * 1024 * 1024));
    assertTrue(failure.getMessage(), failure.getMessage().contains("at most "
        + EditorSyncDocuments.MAX_DOCUMENTS));
  }

  @Test
  public void documentsMustBeSortedByKindThenIdAndUniquePerKindAndId() {
    JsonObject unsorted = EditorSyncTestProjects.menuProject("fixture", "{\"components\":[]}");
    unsorted.getAsJsonArray("documents").add(
        EditorSyncTestProjects.document("menu", "aaa", "{\"components\":[]}"));
    EditorSyncTestProjects.sign(unsorted);
    IllegalArgumentException outOfOrder = assertThrows(IllegalArgumentException.class,
        () -> EditorSyncProject.validated(unsorted, 1024 * 1024));
    assertTrue(outOfOrder.getMessage(), outOfOrder.getMessage().contains("sorted"));

    JsonObject duplicated = EditorSyncTestProjects.menuProject("fixture", "{\"components\":[]}");
    duplicated.getAsJsonArray("documents").add(
        EditorSyncTestProjects.document("menu", "fixture", "{\"components\":[]}"));
    EditorSyncTestProjects.sign(duplicated);
    IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
        () -> EditorSyncProject.validated(duplicated, 1024 * 1024));
    assertTrue(duplicate.getMessage(), duplicate.getMessage().contains("duplicate"));
  }

  @Test
  public void documentKindsMustBeLowercaseSlugs() {
    JsonObject project = EditorSyncTestProjects.menuProject("fixture", "{\"components\":[]}");
    project.getAsJsonArray("documents").get(0).getAsJsonObject()
        .addProperty("kind", "Menu");
    EditorSyncTestProjects.sign(project);

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> EditorSyncProject.validated(project, 1024 * 1024));
    assertTrue(failure.getMessage(), failure.getMessage().contains("slug"));
  }

  @Test
  public void documentJsonIsLimitedToTwoMebibytesOfUtf8() {
    String oversized = "☃".repeat((2 * 1024 * 1024 / 3) + 1);
    JsonObject project = EditorSyncTestProjects.menuProject("fixture",
        "{\"comment\":\"" + oversized + "\"}");

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> EditorSyncProject.validated(project, 32 * 1024 * 1024));
    assertTrue(failure.getMessage(),
        failure.getMessage().contains(String.valueOf(EditorSyncDocuments.MAX_DOCUMENT_BYTES)));
  }

  @Test
  public void revisionsAreRequiredExactlyForServerVersionedKinds() {
    JsonObject project = EditorSyncTestProjects.menuProject("fixture", "{\"components\":[]}");
    project.getAsJsonArray("documents").get(0).getAsJsonObject().addProperty("revision", 1L);
    EditorSyncTestProjects.sign(project);
    IllegalArgumentException menuRevision = assertThrows(IllegalArgumentException.class,
        () -> EditorSyncProject.validated(project, 1024 * 1024));
    assertTrue(menuRevision.getMessage(), menuRevision.getMessage().contains("unversioned"));

    JsonObject versionedProject = EditorSyncTestProjects.menuProject(
        "fixture", "{\"components\":[]}");
    JsonArray versionedDocuments = new JsonArray();
    versionedDocuments.add(EditorSyncTestProjects.document("hologram", "fixture", "{}"));
    versionedProject.add("documents", versionedDocuments);
    EditorSyncTestProjects.sign(versionedProject);
    IllegalArgumentException missingRevision = assertThrows(IllegalArgumentException.class,
        () -> EditorSyncProject.validated(versionedProject, 1024 * 1024));
    assertTrue(missingRevision.getMessage(), missingRevision.getMessage().contains("missing"));

    versionedDocuments.get(0).getAsJsonObject().addProperty("revision", 1L);
    EditorSyncTestProjects.sign(versionedProject);
    assertEquals("fixture",
        EditorSyncProject.validated(versionedProject, 1024 * 1024).subjectId());
  }

  @Test
  public void genericBuilderPreservesCanonicalDocumentOrderAndRevisionShape() {
    List<EditorSyncDocuments.Entry> entries = List.of(
        new EditorSyncDocuments.Entry("menu", "shop/main", null, "{\"components\":[]}"),
        new EditorSyncDocuments.Entry("panel", "shop", 4L, "{\"id\":\"shop\"}"));

    JsonArray documents = EditorSyncDocuments.build(entries);

    assertEquals("menu", documents.get(0).getAsJsonObject().get("kind").getAsString());
    assertEquals("shop/main", documents.get(0).getAsJsonObject().get("id").getAsString());
    assertEquals(4L, documents.get(1).getAsJsonObject().get("revision").getAsLong());
  }
}
