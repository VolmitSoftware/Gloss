package art.arcane.gloss.editor.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Comparator;
import java.util.List;

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
    JsonArray documentKinds = new JsonArray();
    documentKinds.add("menu");
    constraints.add("documentKinds", documentKinds);
    constraints.add("createDocumentKinds", new JsonArray());
    constraints.addProperty("newImagePrefix", "sync/menus/" + id + "/");
    constraints.addProperty("allowDeletes", false);
    project.add("constraints", constraints);
    project.add("warnings", new JsonArray());
    return sign(project);
  }

  static JsonObject workspaceProject(JsonObject... documents) {
    JsonObject project = new JsonObject();
    project.addProperty("format", EditorSyncJson.PROJECT_FORMAT);
    project.addProperty("version", EditorSyncJson.PROTOCOL_VERSION);
    project.addProperty("kind", "workspace");
    project.addProperty("subjectId", EditorSyncContentSnapshotBuilder.WORKSPACE_SUBJECT_ID);
    List<JsonObject> ordered = java.util.Arrays.stream(documents)
        .sorted(Comparator.comparing((JsonObject entry) -> entry.get("kind").getAsString())
            .thenComparing(entry -> entry.get("id").getAsString()))
        .toList();
    JsonArray values = new JsonArray();
    ordered.forEach(values::add);
    project.add("documents", values);
    project.add("images", new JsonArray());
    JsonObject constraints = new JsonObject();
    constraints.addProperty("subjectId", EditorSyncContentSnapshotBuilder.WORKSPACE_SUBJECT_ID);
    JsonArray documentKinds = new JsonArray();
    EditorSyncDocumentKind.ORDERED_WIRE_NAMES.forEach(documentKinds::add);
    JsonArray createDocumentKinds = new JsonArray();
    EditorSyncDocumentKind.ORDERED_WIRE_NAMES.forEach(createDocumentKinds::add);
    constraints.add("documentKinds", documentKinds);
    constraints.add("createDocumentKinds", createDocumentKinds);
    constraints.addProperty("allowDeletes", true);
    project.add("constraints", constraints);
    project.add("warnings", new JsonArray());
    return sign(project);
  }

  static JsonObject menuDocument(String id, String source) {
    JsonObject document = document("menu", id, source);
    document.addProperty("baseRevision", EditorSyncDocuments.contentRevision(source));
    return document;
  }

  static JsonObject editedMenuDocument(String id, String source, String baseSource) {
    JsonObject document = document("menu", id, source);
    document.addProperty("baseRevision", EditorSyncDocuments.contentRevision(baseSource));
    return document;
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
