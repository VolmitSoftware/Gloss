package art.arcane.gloss.editor.sync;

import com.google.gson.JsonObject;

import java.util.Objects;

/** A merged workspace project to validate, and the base revision it was merged onto. */
record EditorSyncPublication(String baseRevision, JsonObject snapshot) {
  EditorSyncPublication {
    if (baseRevision == null || baseRevision.isBlank()) {
      throw new IllegalArgumentException("publication baseRevision must not be blank");
    }
    snapshot = Objects.requireNonNull(snapshot, "snapshot").deepCopy();
  }
}
