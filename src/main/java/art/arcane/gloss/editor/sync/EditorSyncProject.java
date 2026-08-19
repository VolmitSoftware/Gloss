package art.arcane.gloss.editor.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record EditorSyncProject(EditorSyncKind kind, String subjectId, String baseRevision,
                                JsonObject json, int encodedBytes) {
  public EditorSyncProject {
    kind = Objects.requireNonNull(kind, "kind");
    if (subjectId == null || subjectId.isBlank()) {
      throw new IllegalArgumentException("subjectId must not be blank");
    }
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
    JsonElement version = copy.get("version");
    if (EditorSyncJson.RETIRED_V1_PROJECT_FORMAT.equals(rawFormat(copy))
        || (version != null && version.isJsonPrimitive() && version.getAsJsonPrimitive().isNumber()
        && version.getAsBigDecimal().compareTo(java.math.BigDecimal.ONE) == 0)) {
      throw new IllegalArgumentException("sync project uses protocol v1 ("
          + EditorSyncJson.RETIRED_V1_PROJECT_FORMAT
          + "); Gloss speaks v2 only. Open a fresh session with a v2 editor and relay.");
    }
    if (!EditorSyncJson.PROJECT_FORMAT.equals(EditorSyncJson.requireString(copy, "format"))) {
      throw new IllegalArgumentException("unsupported sync project format");
    }
    if (EditorSyncJson.requireInt(copy, "version") != EditorSyncJson.PROTOCOL_VERSION) {
      throw new IllegalArgumentException("unsupported sync project version");
    }
    EditorSyncDocuments.requireHandledKinds(EditorSyncDocuments.parse(copy));
    EditorSyncKind kind = EditorSyncKind.parse(EditorSyncJson.requireString(copy, "kind"));
    String subjectId = EditorSyncJson.requireString(copy, "subjectId");
    String baseRevision = EditorSyncJson.requireString(copy, "baseRevision");
    if (!baseRevision.equals(EditorSyncJson.revision(copy))) {
      throw new IllegalArgumentException("sync project baseRevision does not match its content");
    }
    return new EditorSyncProject(kind, subjectId, baseRevision, copy, encodedBytes);
  }

  private static String rawFormat(JsonObject project) {
    JsonElement format = project.get("format");
    return format != null && format.isJsonPrimitive() && format.getAsJsonPrimitive().isString()
        ? format.getAsString()
        : null;
  }
}
