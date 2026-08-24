package art.arcane.gloss.editor.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class EditorSyncDocuments {
  static final int MAX_DOCUMENTS = 512;
  static final int MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;
  static final int MAX_DOCUMENT_ID_CHARS = 256;
  static final String MENU_KIND = EditorSyncDocumentKind.MENU.wireName();
  static final String PANEL_KIND = EditorSyncDocumentKind.PANEL.wireName();

  private EditorSyncDocuments() {
  }

  record Entry(String kind, String id, Long revision, String json) {
  }

  static List<Entry> parse(JsonObject project) {
    JsonArray values = EditorSyncJson.requireArray(project, "documents");
    if (values.size() > MAX_DOCUMENTS) {
      throw new IllegalArgumentException("documents must contain at most "
          + MAX_DOCUMENTS + " entries");
    }
    List<Entry> documents = new ArrayList<>(values.size());
    Set<String> seen = new HashSet<>();
    String previousSortKey = null;
    for (JsonElement value : values) {
      Entry entry = parseEntry(value);
      String sortKey = entry.kind() + '\0' + entry.id();
      if (!seen.add(sortKey)) {
        throw new IllegalArgumentException("duplicate sync document: "
            + entry.kind() + " " + entry.id());
      }
      if (previousSortKey != null && previousSortKey.compareTo(sortKey) > 0) {
        throw new IllegalArgumentException("sync documents must be sorted by kind then id");
      }
      previousSortKey = sortKey;
      documents.add(entry);
    }
    return List.copyOf(documents);
  }

  static void requireHandledKinds(List<Entry> documents) {
    for (Entry entry : documents) {
      EditorSyncDocumentKind.parseWireName(entry.kind());
    }
  }

  static Map<String, String> menuSources(List<Entry> documents) {
    Map<String, String> menus = new LinkedHashMap<>();
    for (Entry entry : documents) {
      if (MENU_KIND.equals(entry.kind())) {
        menus.put(entry.id(), entry.json());
      }
    }
    return menus;
  }

  static List<Entry> ofKind(List<Entry> documents, String kind) {
    List<Entry> matched = new ArrayList<>();
    for (Entry entry : documents) {
      if (kind.equals(entry.kind())) {
        matched.add(entry);
      }
    }
    return matched;
  }

  static JsonObject entry(String kind, String id, String json) {
    JsonObject entry = new JsonObject();
    entry.addProperty("kind", kind);
    entry.addProperty("id", id);
    entry.addProperty("json", json);
    return entry;
  }

  static JsonObject entry(String kind, String id, long revision, String json) {
    JsonObject entry = entry(kind, id, json);
    entry.addProperty("revision", revision);
    return entry;
  }

  static JsonArray build(List<Entry> sortedDocuments) {
    JsonArray documents = new JsonArray();
    for (Entry document : sortedDocuments) {
      documents.add(document.revision() == null
          ? entry(document.kind(), document.id(), document.json())
          : entry(document.kind(), document.id(), document.revision(), document.json()));
    }
    return documents;
  }

  private static Entry parseEntry(JsonElement value) {
    if (!value.isJsonObject()) {
      throw new IllegalArgumentException("sync document entry must be an object");
    }
    JsonObject entry = value.getAsJsonObject();
    Set<String> keys = entry.keySet();
    if (!keys.equals(Set.of("kind", "id", "json"))
        && !keys.equals(Set.of("kind", "id", "revision", "json"))) {
      throw new IllegalArgumentException(
          "sync document entry contains missing or unsupported fields");
    }
    String kind = EditorSyncJson.requireString(entry, "kind");
    if (!EditorSyncKind.WIRE_KIND_PATTERN.matcher(kind).matches()) {
      throw new IllegalArgumentException("sync document kind must be a sync v3 slug: " + kind);
    }
    EditorSyncDocumentKind.parseWireName(kind);
    String id = EditorSyncJson.requireString(entry, "id");
    if (id.length() > MAX_DOCUMENT_ID_CHARS) {
      throw new IllegalArgumentException("sync document id exceeds "
          + MAX_DOCUMENT_ID_CHARS + " characters");
    }
    String json = EditorSyncJson.requireString(entry, "json");
    if (json.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
      throw new IllegalArgumentException("sync document exceeds " + MAX_DOCUMENT_BYTES
          + " bytes: " + kind + " " + id);
    }
    Long revision = null;
    if (entry.has("revision")) {
      long parsed = EditorSyncJson.requireSafeLong(entry, "revision");
      if (parsed < 1L) {
        throw new IllegalArgumentException("sync document revision must be positive: "
            + kind + " " + id);
      }
      revision = parsed;
    }
    EditorSyncDocumentKind documentKind = EditorSyncDocumentKind.parseWireName(kind);
    String canonicalId = documentKind.canonicalId(id);
    if (documentKind.versioned() != (revision != null)) {
      throw new IllegalArgumentException(documentKind.versioned()
          ? "versioned sync document is missing its entry revision: " + kind + " " + id
          : "unversioned sync document cannot carry an entry revision: " + kind + " " + id);
    }
    return new Entry(kind, canonicalId, revision, json);
  }
}
