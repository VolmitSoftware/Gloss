package art.arcane.gloss.editor.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;

record EditorSyncPendingAck(long publicationRevision, String status, String message,
                            JsonObject serverProject, List<Conflict> conflicts) {
  EditorSyncPendingAck {
    if (publicationRevision < 1L) {
      throw new IllegalArgumentException("publicationRevision must be positive");
    }
    if (!Objects.equals(status, "applied") && !Objects.equals(status, "conflict")
        && !Objects.equals(status, "rejected")) {
      throw new IllegalArgumentException("invalid editor sync acknowledgement status");
    }
    if (message == null || message.isBlank() || message.length() > 1000) {
      throw new IllegalArgumentException("acknowledgement message is invalid");
    }
    if ((status.equals("applied") || status.equals("conflict")) && serverProject == null) {
      throw new IllegalArgumentException("applied/conflict acknowledgement requires a server project");
    }
    if (status.equals("rejected") && serverProject != null) {
      throw new IllegalArgumentException("rejected acknowledgement cannot include a server project");
    }
    serverProject = serverProject == null ? null : serverProject.deepCopy();
    conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
    if (conflicts.size() > EditorSyncDocuments.MAX_DOCUMENTS) {
      throw new IllegalArgumentException("acknowledgement conflict list is too large");
    }
  }

  static JsonArray conflictsJson(List<Conflict> conflicts) {
    JsonArray values = new JsonArray();
    for (Conflict conflict : conflicts) {
      JsonObject value = new JsonObject();
      value.addProperty("kind", conflict.kind());
      value.addProperty("id", conflict.id());
      values.add(value);
    }
    return values;
  }

  EditorSyncProject validatedServerProject(int maximumBytes) {
    return serverProject == null ? null : EditorSyncProject.validated(serverProject, maximumBytes);
  }

  record Conflict(String kind, String id) {
    Conflict {
      kind = EditorSyncDocumentKind.parseWireName(kind).wireName();
      if (id == null || id.isBlank() || id.length() > EditorSyncDocuments.MAX_DOCUMENT_ID_CHARS) {
        throw new IllegalArgumentException("conflicting document id is invalid");
      }
    }
  }
}
