package art.arcane.gloss.editor.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Comparator;
import java.util.List;

final class EditorSyncTestProjects {
  private EditorSyncTestProjects() {
  }

  /** A workspace holding one menu. */
  static JsonObject menuProject(String id, String source) {
    return workspaceProject(document("menu", id, source));
  }

  static JsonObject workspaceProject(JsonObject... documents) {
    JsonObject project = new JsonObject();
    project.addProperty("format", EditorSyncJson.PROJECT_FORMAT);
    project.addProperty("version", EditorSyncJson.PROJECT_VERSION);
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

  /** A small distinct PNG per seed. */
  static byte[] png(int seed) {
    java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(4, 4,
        java.awt.image.BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, 0xff000000 | (seed * 7919));
    image.setRGB(1, 1, 0xff000000 | (seed * 104729));
    try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
      javax.imageio.ImageIO.write(image, "png", output);
      return output.toByteArray();
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  static JsonObject sign(JsonObject project) {
    project.addProperty("baseRevision", EditorSyncJson.revision(project));
    return project;
  }
}
