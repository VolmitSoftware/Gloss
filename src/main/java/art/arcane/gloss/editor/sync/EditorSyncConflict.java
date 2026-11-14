package art.arcane.gloss.editor.sync;

import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * One publish entry that did not land. {@code reason} is {@code changed} (the server copy moved), {@code deleted}
 * (the server removed it) or {@code exists} (a create whose key now exists). Images use kind {@value #IMAGE_KIND}
 * with the path as id.
 */
public record EditorSyncConflict(String kind, String id, String name, String reason) {
  public static final String IMAGE_KIND = "image";

  public EditorSyncConflict {
    if (!IMAGE_KIND.equals(kind)) {
      kind = EditorSyncDocumentKind.parseWireName(kind).wireName();
    }
    if (id == null || id.isBlank() || id.length() > 512) {
      throw new IllegalArgumentException("conflicting document id is invalid");
    }
    name = name == null || name.isBlank() ? id : name;
    if (!Objects.equals(reason, "changed") && !Objects.equals(reason, "deleted") && !Objects.equals(reason, "exists")) {
      throw new IllegalArgumentException("conflict reason is invalid");
    }
  }

  String label() {
    return kind + " " + id;
  }

  public JsonObject json() {
    JsonObject value = new JsonObject();
    value.addProperty("kind", kind);
    value.addProperty("id", id);
    value.addProperty("name", name);
    value.addProperty("reason", reason);
    return value;
  }
}
