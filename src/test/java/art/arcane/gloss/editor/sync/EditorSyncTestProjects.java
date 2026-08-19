package art.arcane.gloss.editor.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Builders for sync protocol-v2 project JSON used across the editor sync tests.
 */
final class EditorSyncTestProjects {
  private EditorSyncTestProjects() {
  }

  static JsonObject menuProject(String id, String source) {
    JsonObject project = new JsonObject();
    project.addProperty("format", EditorSyncJson.PROJECT_FORMAT);
    project.addProperty("version", EditorSyncJson.PROTOCOL_VERSION);
    project.addProperty("kind", "menu");
    project.addProperty("subjectId", id);
    JsonArray documents = new JsonArray();
    documents.add(document("menu", id, source));
    project.add("documents", documents);
    project.add("images", new JsonArray());
    JsonObject constraints = new JsonObject();
    constraints.addProperty("subjectId", id);
    JsonArray menuIds = new JsonArray();
    menuIds.add(id);
    constraints.add("menuIds", menuIds);
    constraints.add("imagePaths", new JsonArray());
    constraints.addProperty("newImagePrefix", "sync/menus/" + id + "/");
    constraints.addProperty("allowDeletes", false);
    project.add("constraints", constraints);
    project.add("warnings", new JsonArray());
    return sign(project);
  }

  static JsonObject document(String kind, String id, String json) {
    JsonObject document = new JsonObject();
    document.addProperty("kind", kind);
    document.addProperty("id", id);
    document.addProperty("json", json);
    return document;
  }

  static JsonObject sign(JsonObject project) {
    project.addProperty("baseRevision", EditorSyncJson.revision(project));
    return project;
  }
}
