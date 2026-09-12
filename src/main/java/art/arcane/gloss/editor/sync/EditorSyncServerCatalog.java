package art.arcane.gloss.editor.sync;

import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.history.HistoryKinds;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * What the editor needs to author a kind it has no dedicated stage for: the kind's JSON Schema and
 * the document Gloss ships as its starting point. Both are read out of the jar once and cached, and
 * both are served only on workspace snapshots, where a generic adapter is the thing that uses them.
 */
final class EditorSyncServerCatalog {
  private static final String SCHEMA_ROOT = "/schema/gloss-";
  private static final String SCHEMA_SUFFIX = ".schema.json";
  private static final String DEFAULTS_ROOT = "/defaults/";
  private static final int MAX_DEFAULT_BYTES = 512 * 1024;

  private static JsonObject schemas;
  private static JsonObject defaults;

  private EditorSyncServerCatalog() {
  }

  static synchronized JsonObject schemas() {
    if (schemas == null) {
      schemas = readSchemas();
    }
    return schemas.deepCopy();
  }

  static synchronized JsonObject defaults() {
    if (defaults == null) {
      defaults = readDefaults();
    }
    return defaults.deepCopy();
  }

  private static JsonObject readSchemas() {
    JsonObject byKind = new JsonObject();
    for (EditorSyncDocumentKind kind : EditorSyncDocumentKind.ORDERED) {
      String source = readSchema(HistoryKinds.collection(kind));
      if (source == null) {
        continue;
      }
      JsonElement parsed = JsonParser.parseString(source);
      if (parsed.isJsonObject()) {
        byKind.add(kind.wireName(), parsed.getAsJsonObject());
      }
    }
    return byKind;
  }

  private static JsonObject readDefaults() {
    JsonObject byKind = new JsonObject();
    int budget = MAX_DEFAULT_BYTES;
    for (ShippedDocumentCatalog.Entry<?> entry : ShippedDocumentCatalog.all()) {
      EditorSyncDocumentKind kind = HistoryKinds.byCollection(entry.kind());
      if (kind == null) {
        continue;
      }
      JsonArray documents = new JsonArray();
      for (String name : entry.names()) {
        String source = read(DEFAULTS_ROOT + entry.kind() + "/" + name + ".json");
        if (source == null) {
          continue;
        }
        budget -= source.length();
        if (budget < 0) {
          break;
        }
        JsonObject document = new JsonObject();
        document.addProperty("id", name);
        document.addProperty("json", source);
        documents.add(document);
      }
      if (!documents.isEmpty()) {
        byKind.add(kind.wireName(), documents);
      }
      if (budget < 0) {
        break;
      }
    }
    return byKind;
  }

  /**
   * Schema files are named after the collection, except where the file predates the folder's plural
   * name ({@code gloss-preview.schema.json} for {@code previews}), so the singular is tried second.
   */
  private static String readSchema(String collection) {
    String named = read(SCHEMA_ROOT + collection + SCHEMA_SUFFIX);
    if (named != null || !collection.endsWith("s")) {
      return named;
    }
    return read(SCHEMA_ROOT + collection.substring(0, collection.length() - 1) + SCHEMA_SUFFIX);
  }

  private static String read(String resource) {
    try (InputStream stream = EditorSyncServerCatalog.class.getResourceAsStream(resource)) {
      return stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      return null;
    }
  }
}
