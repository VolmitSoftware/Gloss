package art.arcane.gloss.editor.sync;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EditorSyncSchemaSectionTest {
  @Test
  public void everyShippedSchemaIsServedUnderItsWireKind() {
    JsonObject schemas = EditorSyncServerCatalog.schemas();

    assertTrue(schemas.keySet().toString(), schemas.has("hologram"));
    assertTrue(schemas.keySet().toString(), schemas.has("bubble-style"));
    assertTrue(schemas.keySet().toString(), schemas.has("container-preview"));
    for (String wireName : schemas.keySet()) {
      assertTrue(wireName, EditorSyncDocumentKind.ORDERED_WIRE_NAMES.contains(wireName));
      assertTrue(wireName, schemas.getAsJsonObject(wireName).has("$schema"));
    }
  }

  @Test
  public void shippedDefaultsAreServedAsTemplatesUnderTheirWireKind() {
    JsonObject defaults = EditorSyncServerCatalog.defaults();

    assertTrue(defaults.keySet().toString(), defaults.has("animation"));
    assertTrue(defaults.keySet().toString(), defaults.has("scoreboard"));
    JsonObject first = defaults.getAsJsonArray("animation").get(0).getAsJsonObject();
    assertEquals(Set.of("id", "json"), first.keySet());
    assertFalse(first.get("json").getAsString().isBlank());
    for (String wireName : defaults.keySet()) {
      assertTrue(wireName, EditorSyncDocumentKind.ORDERED_WIRE_NAMES.contains(wireName));
    }
  }

  @Test
  public void aWorkspaceSnapshotCarriesTheCatalogAndAFocusedOneDoesNot() throws Exception {
    java.nio.file.Path data = java.nio.file.Files.createTempDirectory("schema-section");
    java.nio.file.Files.createDirectories(data.resolve("menus"));
    java.nio.file.Files.writeString(data.resolve("menus/shop.json"), "{\"components\":[]}");
    EditorSyncContentSnapshotBuilder builder = new EditorSyncContentSnapshotBuilder(data);
    EditorSyncContentSnapshotBuilder.ServerSections sections =
        new EditorSyncContentSnapshotBuilder.ServerSections() {
          @Override
          public JsonObject schemas() {
            return EditorSyncServerCatalog.schemas();
          }

          @Override
          public JsonObject defaults() {
            return EditorSyncServerCatalog.defaults();
          }
        };

    EditorSyncProject workspace = builder.open(EditorSyncKind.WORKSPACE, "workspace",
        8 * 1024 * 1024, sections);
    EditorSyncProject menu = builder.open(EditorSyncKind.MENU, "shop", 8 * 1024 * 1024);

    assertTrue(workspace.json().has("schemas"));
    assertTrue(workspace.json().has("defaults"));
    assertFalse(menu.json().has("schemas"));
    assertFalse(menu.json().has("defaults"));
  }
}
