package art.arcane.gloss.editor.sync;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** The whole workspace as one JSON project: what a publication is merged onto and validated against. */
public record EditorSyncProject(String baseRevision, JsonObject json, int encodedBytes) {
  public EditorSyncProject {
    if (baseRevision == null || !baseRevision.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("baseRevision must be a SHA-256 revision");
    }
    json = Objects.requireNonNull(json, "json").deepCopy();
    if (encodedBytes < 1) {
      throw new IllegalArgumentException("encodedBytes must be positive");
    }
  }

  public static EditorSyncProject validated(JsonObject json, int maximumBytes) {
    JsonObject copy = Objects.requireNonNull(json, "json").deepCopy();
    EditorSyncJsonLimits.validate(copy);
    int encodedBytes = EditorSyncJson.canonical(copy).getBytes(StandardCharsets.UTF_8).length;
    if (encodedBytes > maximumBytes) {
      throw new EditorSyncProjectTooLargeException(encodedBytes, maximumBytes);
    }
    if (!EditorSyncJson.PROJECT_FORMAT.equals(EditorSyncJson.requireString(copy, "format"))) {
      throw new IllegalArgumentException("unsupported sync project format");
    }
    if (EditorSyncJson.requireInt(copy, "version") != EditorSyncJson.PROJECT_VERSION) {
      throw new IllegalArgumentException("unsupported sync project version");
    }
    if (!EditorSyncContentSnapshotBuilder.WORKSPACE_KIND.equals(EditorSyncJson.requireString(copy, "kind"))
        || !EditorSyncContentSnapshotBuilder.WORKSPACE_SUBJECT_ID.equals(EditorSyncJson.requireString(copy, "subjectId"))) {
      throw new IllegalArgumentException("sync project must be the workspace");
    }
    EditorSyncDocuments.parse(copy);
    String baseRevision = EditorSyncJson.requireString(copy, "baseRevision");
    if (!baseRevision.equals(EditorSyncJson.revision(copy))) {
      throw new IllegalArgumentException("sync project baseRevision does not match its content");
    }
    return new EditorSyncProject(baseRevision, copy, encodedBytes);
  }

}
