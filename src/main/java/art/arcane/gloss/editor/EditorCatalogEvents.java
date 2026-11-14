package art.arcane.gloss.editor;

import art.arcane.gloss.editor.sync.EditorWorkspace;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.function.Function;

/** The JSON of a workspace delta: the {@code catalog} event frame and the delta part of a publish answer. */
final class EditorCatalogEvents {
  private EditorCatalogEvents() {
  }

  /** A {@code catalog} event for the delta, every entry marked with {@code source}. */
  static JsonObject catalog(EditorWorkspace.Delta delta, EditorWorkspace.Source source) {
    return catalog(delta, (String path) -> source);
  }

  /** A {@code catalog} event whose entries are each marked with the source of their data-folder path. */
  static JsonObject catalog(EditorWorkspace.Delta delta, Function<String, EditorWorkspace.Source> source) {
    JsonObject event = delta(delta, source);
    event.addProperty("type", "catalog");
    return event;
  }

  /** The delta's lists; entries carry {@code source} when it is given. Empty lists are left out. */
  static JsonObject delta(EditorWorkspace.Delta delta, Function<String, EditorWorkspace.Source> source) {
    JsonObject json = new JsonObject();
    JsonArray documents = new JsonArray();
    for (EditorWorkspace.DocumentRow row : delta.documents()) {
      documents.add(sourced(row.json(), source == null ? null : source.apply(row.path())));
    }
    JsonArray deleted = new JsonArray();
    for (EditorWorkspace.DocumentRef ref : delta.deleted()) {
      deleted.add(sourced(ref.json(), source == null ? null : source.apply(ref.path())));
    }
    JsonArray images = new JsonArray();
    for (EditorWorkspace.ImageRow row : delta.images()) {
      images.add(sourced(row.json(), source == null ? null : source.apply("images/" + row.path())));
    }
    JsonArray removed = new JsonArray();
    for (String path : delta.removedImages()) {
      JsonObject entry = new JsonObject();
      entry.addProperty("path", path);
      removed.add(sourced(entry, source == null ? null : source.apply("images/" + path)));
    }
    if (!documents.isEmpty()) {
      json.add("documents", documents);
    }
    if (!deleted.isEmpty()) {
      json.add("deleted", deleted);
    }
    if (!images.isEmpty()) {
      json.add("images", images);
    }
    if (!removed.isEmpty()) {
      json.add("removedImages", removed);
    }
    if (delta.metaHash() != null) {
      json.addProperty("metaHash", delta.metaHash());
    }
    if (delta.notesHash() != null) {
      json.addProperty("notesHash", delta.notesHash());
    }
    return json;
  }

  private static JsonObject sourced(JsonObject entry, EditorWorkspace.Source source) {
    if (source != null) {
      entry.addProperty("source", source.wireName());
    }
    return entry;
  }
}
