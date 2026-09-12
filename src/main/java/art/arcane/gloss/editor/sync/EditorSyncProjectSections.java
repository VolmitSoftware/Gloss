package art.arcane.gloss.editor.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;

/**
 * The server-owned sections of a sync project beyond its documents and images: the warnings the
 * editor's Problems panel reads, the version history its History panel lists, and the schema plus
 * shipped default a generic editor adapter needs for a kind it has no stage for.
 *
 * <p>None of these round-trip: the server rebuilds them on every snapshot and ignores whatever the
 * editor sends back, which is why the concurrency checks compare documents and images only.
 */
record EditorSyncProjectSections(List<String> warnings, JsonArray history, JsonObject schemas,
                                 JsonObject defaults) {
  static final int MAX_HISTORY_PER_DOCUMENT = 20;
  static final int MAX_HISTORY_ENTRIES = 2000;

  EditorSyncProjectSections {
    warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    history = Objects.requireNonNull(history, "history").deepCopy();
    schemas = Objects.requireNonNull(schemas, "schemas").deepCopy();
    defaults = Objects.requireNonNull(defaults, "defaults").deepCopy();
    if (history.size() > MAX_HISTORY_ENTRIES) {
      throw new IllegalArgumentException("sync project carries too many history entries");
    }
  }

  static EditorSyncProjectSections warningsOnly(List<String> warnings) {
    return new EditorSyncProjectSections(warnings, new JsonArray(), new JsonObject(),
        new JsonObject());
  }

  static JsonObject historyEntry(String kind, String id, long version, String source, long bytes) {
    JsonObject entry = new JsonObject();
    entry.addProperty("kind", kind);
    entry.addProperty("id", id);
    entry.addProperty("version", version);
    entry.addProperty("source", source);
    entry.addProperty("bytes", bytes);
    return entry;
  }
}
