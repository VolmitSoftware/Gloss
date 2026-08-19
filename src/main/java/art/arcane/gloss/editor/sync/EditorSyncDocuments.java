package art.arcane.gloss.editor.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The sync v2 {@code documents[]} codec: one uniform array of
 * {@code {kind, id, [revision], json}} entries replaces v1's {@code menus[]}
 * array and conditional {@code board} object. Entries are sorted by kind then
 * id, unique per (kind, id), and each document's JSON source is limited to
 * {@link #MAX_DOCUMENT_BYTES}. Kinds are open slugs on the wire; Gloss rejects
 * the ones it has no codec for with an actionable per-document error.
 */
final class EditorSyncDocuments {
  static final int MAX_DOCUMENTS = 512;
  static final int MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;
  static final int MAX_DOCUMENT_ID_CHARS = 256;
  static final String MENU_KIND = "menu";
  static final String PANEL_KIND = "panel";

  private EditorSyncDocuments() {
  }

  record Entry(String kind, String id, Long revision, String json) {
  }

  static List<Entry> parse(JsonObject project) {
    JsonArray values = EditorSyncJson.requireArray(project, "documents");
    if (values.isEmpty() || values.size() > MAX_DOCUMENTS) {
      throw new IllegalArgumentException("documents must contain between 1 and "
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
      if (!MENU_KIND.equals(entry.kind()) && !PANEL_KIND.equals(entry.kind())) {
        throw new IllegalArgumentException("sync document '" + entry.id() + "' has kind '"
            + entry.kind() + "', which this Gloss build cannot edit; update Gloss");
      }
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

  /**
   * The parsed panel document of a project, or null when the project carries
   * none. Panel document text is canonical JSON, so the returned object
   * canonicalizes back to the wire bytes.
   */
  static JsonObject panelJson(JsonObject project) {
    for (Entry entry : parse(project)) {
      if (PANEL_KIND.equals(entry.kind())) {
        return parsePanelText(entry);
      }
    }
    return null;
  }

  static JsonObject parsePanelText(Entry entry) {
    JsonElement parsed;
    try {
      parsed = JsonParser.parseString(entry.json());
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException("panel document is not valid JSON: " + entry.id(),
          failure);
    }
    if (!parsed.isJsonObject()) {
      throw new IllegalArgumentException("panel document must be a JSON object: " + entry.id());
    }
    if (!entry.json().equals(EditorSyncJson.canonical(parsed))) {
      throw new IllegalArgumentException("panel document must be canonical JSON text: "
          + entry.id());
    }
    return parsed.getAsJsonObject();
  }

  /**
   * Builds the sorted v2 documents array from menu sources plus an optional
   * panel definition. Menu ids arrive pre-sorted (TreeMap order) and
   * {@code "menu" < "panel"}, so appending the panel entry keeps the
   * kind-then-id ordering.
   */
  static JsonArray build(Map<String, String> sortedMenus, JsonObject panelJson, String panelId) {
    JsonArray documents = new JsonArray();
    for (Map.Entry<String, String> menu : sortedMenus.entrySet()) {
      documents.add(entry(MENU_KIND, menu.getKey(), menu.getValue()));
    }
    if (panelJson != null) {
      documents.add(entry(PANEL_KIND, panelId, EditorSyncJson.canonical(panelJson)));
    }
    return documents;
  }

  private static JsonObject entry(String kind, String id, String json) {
    JsonObject entry = new JsonObject();
    entry.addProperty("kind", kind);
    entry.addProperty("id", id);
    entry.addProperty("json", json);
    return entry;
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
      throw new IllegalArgumentException("sync document kind must be a sync v2 slug: " + kind);
    }
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
    return new Entry(kind, id, revision, json);
  }
}
