package art.arcane.gloss.editor.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
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
  private static final Set<Set<String>> ACCEPTED_ENTRY_KEYS = Set.of(
      Set.of("kind", "id", "json"),
      Set.of("kind", "id", "revision", "json"),
      Set.of("kind", "id", "json", "baseRevision"),
      Set.of("kind", "id", "revision", "json", "baseRevision"));

  private EditorSyncDocuments() {
  }

  record Entry(String kind, String id, Long revision, String json, String baseRevision) {
    Entry withBaseRevision(String revision) {
      return new Entry(kind, id, this.revision, json, revision);
    }
  }

  /**
   * The content revision of one document: SHA-256 over its canonical JSON, so an entry the
   * editor received and returned unchanged compares equal to the file the server re-reads no
   * matter how either side laid the text out.
   */
  static String contentRevision(String json) {
    JsonElement parsed = JsonParser.parseString(json);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return "sha256:" + HexFormat.of().formatHex(digest.digest(
          EditorSyncJson.canonical(parsed).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
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

  /**
   * True when every entry in {@code project} carries a per-document base revision, which is what
   * lets a publication be reconciled document by document instead of failing as a whole.
   */
  static boolean everyEntryCarriesBaseRevision(JsonObject project) {
    try {
      for (Entry entry : parse(project)) {
        if (entry.baseRevision() == null) {
          return false;
        }
      }
      return true;
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  static void requireHandledKinds(List<Entry> documents) {
    for (Entry entry : documents) {
      EditorSyncDocumentKind.parseWireName(entry.kind());
    }
  }

  /** The kind this build handles, or null when the wire name belongs to a kind it does not. */
  static EditorSyncDocumentKind handledKind(String wireName) {
    for (EditorSyncDocumentKind kind : EditorSyncDocumentKind.values()) {
      if (kind.wireName().equals(wireName)) {
        return kind;
      }
    }
    return null;
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

  static JsonObject entry(Entry document) {
    JsonObject entry = new JsonObject();
    entry.addProperty("kind", document.kind());
    entry.addProperty("id", document.id());
    if (document.revision() != null) {
      entry.addProperty("revision", document.revision());
    }
    entry.addProperty("json", document.json());
    if (document.baseRevision() != null) {
      entry.addProperty("baseRevision", document.baseRevision());
    }
    return entry;
  }

  static JsonArray build(List<Entry> sortedDocuments) {
    JsonArray documents = new JsonArray();
    for (Entry document : sortedDocuments) {
      documents.add(entry(document));
    }
    return documents;
  }

  private static Entry parseEntry(JsonElement value) {
    if (!value.isJsonObject()) {
      throw new IllegalArgumentException("sync document entry must be an object");
    }
    JsonObject entry = value.getAsJsonObject();
    Set<String> keys = entry.keySet();
    if (!ACCEPTED_ENTRY_KEYS.contains(keys)) {
      throw new IllegalArgumentException(
          "sync document entry contains missing or unsupported fields");
    }
    String kind = EditorSyncJson.requireString(entry, "kind");
    if (!EditorSyncKind.WIRE_KIND_PATTERN.matcher(kind).matches()) {
      throw new IllegalArgumentException("sync document kind must be a sync v3 slug: " + kind);
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
    String baseRevision = null;
    if (entry.has("baseRevision")) {
      baseRevision = EditorSyncJson.requireString(entry, "baseRevision");
      if (!baseRevision.matches("sha256:[0-9a-f]{64}")) {
        throw new IllegalArgumentException("sync document baseRevision must be a SHA-256 revision: "
            + kind + " " + id);
      }
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
    EditorSyncDocumentKind documentKind = handledKind(kind);
    if (documentKind == null) {
      return new Entry(kind, id, revision, json, baseRevision);
    }
    String canonicalId = documentKind.canonicalId(id);
    if (documentKind.versioned() != (revision != null)) {
      throw new IllegalArgumentException(documentKind.versioned()
          ? "versioned sync document is missing its entry revision: " + kind + " " + id
          : "unversioned sync document cannot carry an entry revision: " + kind + " " + id);
    }
    return new Entry(kind, canonicalId, revision, json, baseRevision);
  }
}
