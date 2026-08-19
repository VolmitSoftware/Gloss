package art.arcane.gloss.editor.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class EditorSyncDocumentsTest {
  @Test
  public void v1ProjectsAreRejectedWithAnActionableProtocolError() {
    JsonObject project = EditorSyncTestProjects.menuProject("fixture", "{\"components\":[]}");
    project.addProperty("format", "holoui-sync-project");
    project.addProperty("version", 1);
    project.remove("documents");
    JsonArray menus = new JsonArray();
    menus.add(EditorSyncTestProjects.document("menu", "fixture", "{\"components\":[]}"));
    project.add("menus", menus);
    EditorSyncTestProjects.sign(project);

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> EditorSyncProject.validated(project, 1024 * 1024));
    assertTrue(failure.getMessage(), failure.getMessage().contains("protocol v1"));
    assertTrue(failure.getMessage(), failure.getMessage().contains("Gloss speaks v2 only"));
  }

  @Test
  public void unknownDocumentKindsAreRejectedByNameNotByShapeCrash() {
    JsonObject project = EditorSyncTestProjects.menuProject("fixture", "{\"components\":[]}");
    JsonArray documents = new JsonArray();
    documents.add(EditorSyncTestProjects.document("hologram", "fixture", "{\"lines\":[]}"));
    documents.addAll(project.getAsJsonArray("documents"));
    project.add("documents", documents);
    EditorSyncTestProjects.sign(project);

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> EditorSyncProject.validated(project, 1024 * 1024));
    assertTrue(failure.getMessage(), failure.getMessage().contains("'hologram'"));
    assertTrue(failure.getMessage(), failure.getMessage().contains("update Gloss"));
  }

  @Test
  public void unknownTopLevelSubjectKindNamesTheKindUsingTheV2Fixture() throws Exception {
    InputStream resource = getClass().getResourceAsStream("/editor-sync-canonical-v2.json");
    if (resource == null) {
      throw new IllegalStateException("missing editor sync canonical fixture");
    }
    JsonObject project;
    try (InputStream input = resource;
         InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
      project = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("project");
    }

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> EditorSyncProject.validated(project, 1024 * 1024));
    assertTrue(failure.getMessage(), failure.getMessage().contains("hologram"));
    assertTrue(failure.getMessage(), failure.getMessage().contains("update Gloss"));
  }

  @Test
  public void documentCollectionIsLimitedToFiveHundredTwelveEntries() {
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
    assertTrue(failure.getMessage(),
        failure.getMessage().contains("between 1 and " + EditorSyncDocuments.MAX_DOCUMENTS));
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
  public void documentKindsMustBeOpenLowercaseSlugs() {
    JsonObject project = EditorSyncTestProjects.menuProject("fixture", "{\"components\":[]}");
    project.getAsJsonArray("documents").add(
        EditorSyncTestProjects.document("Menu", "fixture", "{\"components\":[]}"));
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
        failure.getMessage().contains("" + EditorSyncDocuments.MAX_DOCUMENT_BYTES));
  }

  @Test
  public void optionalServerOwnedRevisionMustBeAPositiveSafeInteger() {
    JsonObject valid = EditorSyncTestProjects.menuProject("fixture", "{\"components\":[]}");
    valid.getAsJsonArray("documents").get(0).getAsJsonObject()
        .addProperty("revision", EditorSyncJson.MAX_SAFE_INTEGER);
    EditorSyncTestProjects.sign(valid);
    assertEquals("fixture", EditorSyncProject.validated(valid, 1024 * 1024).subjectId());

    JsonObject invalid = EditorSyncTestProjects.menuProject("fixture", "{\"components\":[]}");
    invalid.getAsJsonArray("documents").get(0).getAsJsonObject().addProperty("revision", 0L);
    EditorSyncTestProjects.sign(invalid);
    assertThrows(IllegalArgumentException.class,
        () -> EditorSyncProject.validated(invalid, 1024 * 1024));
  }

  @Test
  public void builderEmitsMenuEntriesThenTheCanonicalPanelEntryInWireOrder() {
    java.util.TreeMap<String, String> menus = new java.util.TreeMap<>();
    menus.put("shop/main", "{\"components\":[]}");
    menus.put("shop/armor", "{\"components\":[]}");
    JsonObject panel = JsonParser.parseString("{\"rootMenuId\":\"shop/main\",\"id\":\"shop\"}")
        .getAsJsonObject();

    JsonArray documents = EditorSyncDocuments.build(menus, panel, "shop");

    assertEquals("[{\"kind\":\"menu\",\"id\":\"shop/armor\",\"json\":\"{\\\"components\\\":[]}\"},"
            + "{\"kind\":\"menu\",\"id\":\"shop/main\",\"json\":\"{\\\"components\\\":[]}\"},"
            + "{\"kind\":\"panel\",\"id\":\"shop\","
            + "\"json\":\"{\\\"id\\\":\\\"shop\\\",\\\"rootMenuId\\\":\\\"shop/main\\\"}\"}]",
        documents.toString());
    JsonObject project = new JsonObject();
    project.add("documents", documents);
    assertEquals(menus, new java.util.TreeMap<>(
        EditorSyncDocuments.menuSources(EditorSyncDocuments.parse(project))));
    assertEquals(EditorSyncJson.canonical(panel),
        EditorSyncJson.canonical(EditorSyncDocuments.panelJson(project)));
  }

  @Test
  public void panelDocumentsMustCarryCanonicalJsonText() {
    JsonObject panelSource = JsonParser.parseString(
        "{\"b\":1,\"a\":2}").getAsJsonObject();
    JsonObject canonicalProject = new JsonObject();
    JsonArray documents = new JsonArray();
    documents.add(EditorSyncTestProjects.document("panel", "fixture",
        EditorSyncJson.canonical(panelSource)));
    canonicalProject.add("documents", documents);
    assertEquals(EditorSyncJson.canonical(panelSource),
        EditorSyncJson.canonical(EditorSyncDocuments.panelJson(canonicalProject)));

    JsonObject nonCanonicalProject = new JsonObject();
    JsonArray nonCanonical = new JsonArray();
    nonCanonical.add(EditorSyncTestProjects.document("panel", "fixture", "{\"b\":1,\"a\":2}"));
    nonCanonicalProject.add("documents", nonCanonical);
    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> EditorSyncDocuments.panelJson(nonCanonicalProject));
    assertTrue(failure.getMessage(), failure.getMessage().contains("canonical JSON text"));

    JsonObject menuOnlyProject = new JsonObject();
    JsonArray menuOnly = new JsonArray();
    menuOnly.add(EditorSyncTestProjects.document("menu", "fixture", "{\"components\":[]}"));
    menuOnlyProject.add("documents", menuOnly);
    assertNull(EditorSyncDocuments.panelJson(menuOnlyProject));
  }
}
