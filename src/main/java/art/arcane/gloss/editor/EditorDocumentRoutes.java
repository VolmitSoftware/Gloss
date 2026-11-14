package art.arcane.gloss.editor;

import art.arcane.gloss.editor.sync.EditorWorkspace;
import art.arcane.gloss.editor.transport.EditorRequest;
import art.arcane.gloss.editor.transport.EditorResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The read endpoints: the paged catalog, bodies by page, batch and single reads, images, meta, notes and history.
 * Reads come from the workspace's last refresh in memory; until the first refresh finished they wait for it on the
 * file lane. Every answer stays within its byte budget, and on the relay within its frame.
 */
final class EditorDocumentRoutes {
  static final int PAGE_ENTRIES = 1000;
  static final int CATALOG_BYTES = 512 * 1024;
  static final int ANSWER_BYTES = 1024 * 1024;
  static final int MAXIMUM_ANSWER_BYTES = 4 * 1024 * 1024;
  static final int RELAY_MARGIN = 16 * 1024;
  static final int MAXIMUM_REFS = 200;
  static final int MAXIMUM_HASHES = 64;
  private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9-]{0,31}");
  private static final Pattern CURSOR = Pattern.compile("[A-Za-z0-9_-]{1,2048}");

  /** The image hashes a folder-limited token sees in one catalog. */
  private record VisibleImages(EditorWorkspace.View view, Set<String> hashes) {
  }

  private final EditorApi api;
  private final Map<List<String>, VisibleImages> visibleImages = new ConcurrentHashMap<>();

  EditorDocumentRoutes(EditorApi api) {
    this.api = api;
  }

  /** The most entry bytes one answer carries on this request's path. */
  private int budget(EditorRequest request, int wanted) {
    if (request.transport() != EditorRequest.Transport.RELAY) {
      return wanted;
    }
    return Math.max(RELAY_MARGIN, Math.min(wanted, api.parts().settings().relayFrameBytes() - RELAY_MARGIN));
  }

  /** Runs {@code work} at once once the workspace was read, else after the first read on the file lane. */
  private CompletionStage<EditorResponse> read(EditorTokens.Token token, EditorApi.FileWork work) throws EditorError {
    if (api.parts().workspace().read()) {
      try {
        return EditorApi.done(work.run());
      } catch (java.io.IOException failure) {
        return EditorApi.done(api.internal(failure));
      }
    }
    return api.files(token, work);
  }

  CompletionStage<EditorResponse> catalog(EditorRequest request, EditorTokens.Token token, String[] segments)
      throws EditorError {
    Map<String, String> query = EditorReplies.query(request);
    unknown(query, Set.of("after"));
    Optional<String> after = cursor(query.get("after"));
    int budget = budget(request, CATALOG_BYTES);
    return read(token, () -> {
      EditorWorkspace.View view = api.parts().workspace().view();
      JsonArray documents = new JsonArray();
      JsonArray images = new JsonArray();
      int bytes = 0;
      int entries = 0;
      String last = null;
      String next = null;
      for (EditorWorkspace.DocumentRow row : view.documents()) {
        String key = "d\n" + row.kind() + "\n" + row.id();
        if (after.isPresent() && compare(key, after.get()) <= 0) {
          continue;
        }
        if (!token.reaches(row.folder())) {
          continue;
        }
        JsonObject json = row.json();
        int size = size(json);
        if (entries == PAGE_ENTRIES || entries > 0 && bytes + size > budget) {
          next = last;
          break;
        }
        documents.add(json);
        bytes += size;
        entries++;
        last = key;
      }
      if (next == null) {
        for (EditorWorkspace.ImageRow row : view.images()) {
          String key = "i\n" + row.path();
          if (after.isPresent() && compare(key, after.get()) <= 0) {
            continue;
          }
          if (!token.reaches(row.folder())) {
            continue;
          }
          JsonObject json = row.json();
          int size = size(json);
          if (entries == PAGE_ENTRIES || entries > 0 && bytes + size > budget) {
            next = last;
            break;
          }
          images.add(json);
          bytes += size;
          entries++;
          last = key;
        }
      }
      JsonObject body = new JsonObject();
      body.add("documents", documents);
      body.add("images", images);
      body.addProperty("metaHash", view.metaHash());
      body.addProperty("notesHash", view.notesHash());
      putCursor(body, next);
      return EditorResponse.json(200, body);
    });
  }

  CompletionStage<EditorResponse> page(EditorRequest request, EditorTokens.Token token, String[] segments)
      throws EditorError {
    Map<String, String> query = EditorReplies.query(request);
    unknown(query, Set.of("after", "maxBytes"));
    Optional<String> after = cursor(query.get("after"));
    int wanted = ANSWER_BYTES;
    if (query.containsKey("maxBytes")) {
      try {
        wanted = Integer.parseInt(query.get("maxBytes"));
      } catch (NumberFormatException invalid) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "maxBytes must be a number.");
      }
      if (wanted < 1024 || wanted > MAXIMUM_ANSWER_BYTES) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "maxBytes must be 1024 to " + MAXIMUM_ANSWER_BYTES + ".");
      }
    }
    int budget = budget(request, wanted);
    boolean relay = request.transport() == EditorRequest.Transport.RELAY;
    return read(token, () -> {
      EditorWorkspace.View view = api.parts().workspace().view();
      JsonArray documents = new JsonArray();
      int bytes = 0;
      String last = null;
      String next = null;
      for (EditorWorkspace.DocumentRow row : view.documents()) {
        String key = "d\n" + row.kind() + "\n" + row.id();
        if (after.isPresent() && compare(key, after.get()) <= 0 || !token.reaches(row.folder())) {
          continue;
        }
        JsonObject json = body(row, view.body(row.kind(), row.id()).orElse("{}"));
        int size = size(json);
        if (!documents.isEmpty() && bytes + size > budget) {
          next = last;
          break;
        }
        if (relay && size > budget) {
          throw new EditorError(EditorError.Code.TOO_LARGE, row.kind() + " " + row.id()
              + " is larger than a relay frame; read it on the direct connection.");
        }
        documents.add(json);
        bytes += size;
        last = key;
      }
      JsonObject body = new JsonObject();
      body.add("documents", documents);
      putCursor(body, next);
      return EditorResponse.json(200, body);
    });
  }

  CompletionStage<EditorResponse> read(EditorRequest request, EditorTokens.Token token, String[] segments)
      throws EditorError {
    JsonObject body = EditorApi.body(request);
    EditorApi.fields(body, Set.of("refs"));
    JsonElement value = body.get("refs");
    if (value == null || !value.isJsonArray() || value.getAsJsonArray().isEmpty()
        || value.getAsJsonArray().size() > MAXIMUM_REFS) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, "refs must hold 1 to " + MAXIMUM_REFS + " documents.");
    }
    JsonArray refs = value.getAsJsonArray();
    Set<String> seen = new HashSet<>();
    for (JsonElement ref : refs) {
      if (!ref.isJsonObject() || !ref.getAsJsonObject().keySet().equals(Set.of("kind", "id"))) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "Each ref is {kind, id}.");
      }
      if (!seen.add(text(ref.getAsJsonObject(), "kind") + "\n" + text(ref.getAsJsonObject(), "id"))) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "refs name a document twice.");
      }
    }
    int budget = budget(request, ANSWER_BYTES);
    return read(token, () -> {
      EditorWorkspace.View view = api.parts().workspace().view();
      JsonArray documents = new JsonArray();
      JsonArray errors = new JsonArray();
      JsonArray remaining = new JsonArray();
      int bytes = 0;
      for (JsonElement element : refs) {
        JsonObject ref = element.getAsJsonObject();
        String kind = text(ref, "kind");
        String id = text(ref, "id");
        if (!remaining.isEmpty()) {
          remaining.add(ref.deepCopy());
          continue;
        }
        if (!KIND.matcher(kind).matches() || id.isEmpty() || id.length() > 256) {
          errors.add(error(kind, id, "invalid-request", "Not a document reference."));
          continue;
        }
        Optional<EditorWorkspace.DocumentRow> row = view.row(kind, id);
        if (row.isEmpty()) {
          errors.add(error(kind, id, "not-found", "No " + kind + " " + id + "."));
          continue;
        }
        if (!token.reaches(row.get().folder())) {
          errors.add(error(kind, id, "folder-denied", "This token cannot reach " + kind + " " + id + "."));
          continue;
        }
        JsonObject json = body(row.get(), view.body(kind, id).orElse("{}"));
        json.add("summary", row.get().json().get("summary"));
        int size = size(json);
        if (request.transport() == EditorRequest.Transport.RELAY && size > budget) {
          errors.add(error(kind, id, "too-large", "Larger than a relay frame; read it on the direct connection."));
          continue;
        }
        if (!documents.isEmpty() && bytes + size > budget) {
          remaining.add(ref.deepCopy());
          continue;
        }
        documents.add(json);
        bytes += size;
      }
      JsonObject answer = new JsonObject();
      answer.add("documents", documents);
      answer.add("errors", errors);
      answer.add("remaining", remaining);
      return EditorResponse.json(200, answer);
    });
  }

  CompletionStage<EditorResponse> document(EditorRequest request, EditorTokens.Token token, String[] segments)
      throws EditorError {
    String kind = segments[1];
    StringBuilder id = new StringBuilder();
    for (int index = 2; index < segments.length; index++) {
      if (index > 2) {
        id.append('/');
      }
      id.append(decode(segments[index]));
    }
    String documentId = id.toString();
    return read(token, () -> {
      EditorWorkspace.View view = api.parts().workspace().view();
      EditorWorkspace.DocumentRow row = view.row(kind, documentId).orElseThrow(() -> new EditorError(
          EditorError.Code.NOT_FOUND, "No " + kind + " " + documentId + "."));
      if (!token.reaches(row.folder())) {
        throw EditorApi.folderDenied(token, kind + " " + documentId);
      }
      JsonObject json = body(row, view.body(kind, documentId).orElse("{}"));
      json.add("summary", row.json().get("summary"));
      if (request.transport() == EditorRequest.Transport.RELAY && size(json) > budget(request, MAXIMUM_ANSWER_BYTES)) {
        throw new EditorError(EditorError.Code.TOO_LARGE, kind + " " + documentId
            + " is larger than a relay frame; read it on the direct connection.");
      }
      return EditorResponse.json(200, json);
    });
  }

  CompletionStage<EditorResponse> images(EditorRequest request, EditorTokens.Token token, String[] segments)
      throws EditorError {
    JsonObject body = EditorApi.body(request);
    EditorApi.fields(body, Set.of("hashes"));
    JsonElement value = body.get("hashes");
    if (value == null || !value.isJsonArray() || value.getAsJsonArray().isEmpty()
        || value.getAsJsonArray().size() > MAXIMUM_HASHES) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, "hashes must hold 1 to " + MAXIMUM_HASHES + " hashes.");
    }
    List<String> hashes = new java.util.ArrayList<>();
    for (JsonElement hash : value.getAsJsonArray()) {
      if (!hash.isJsonPrimitive() || !HASH.matcher(hash.getAsString()).matches()) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "hashes are sha256:<64 hex>.");
      }
      if (!hashes.contains(hash.getAsString())) {
        hashes.add(hash.getAsString());
      }
    }
    int budget = budget(request, ANSWER_BYTES);
    return read(token, () -> {
      EditorWorkspace.View view = api.parts().workspace().view();
      Set<String> visible = visibleImageHashes(view, token);
      JsonArray images = new JsonArray();
      JsonArray missing = new JsonArray();
      JsonArray remaining = new JsonArray();
      int bytes = 0;
      for (String hash : hashes) {
        if (!remaining.isEmpty()) {
          remaining.add(hash);
          continue;
        }
        EditorWorkspace.Blob blob = visible.contains(hash) ? view.blobs().get(hash) : null;
        if (blob == null) {
          missing.add(hash);
          continue;
        }
        JsonObject json = blob(blob);
        int size = size(json);
        if (!images.isEmpty() && bytes + size > budget) {
          remaining.add(hash);
          continue;
        }
        images.add(json);
        bytes += size;
      }
      JsonObject answer = new JsonObject();
      answer.add("images", images);
      answer.add("missing", missing);
      answer.add("remaining", remaining);
      return EditorResponse.json(200, answer);
    });
  }

  CompletionStage<EditorResponse> image(EditorRequest request, EditorTokens.Token token, String[] segments)
      throws EditorError {
    String hex = segments[1];
    if (!HEX.matcher(hex).matches()) {
      throw new EditorError(EditorError.Code.NOT_FOUND, "Images are named by 64 hex characters.");
    }
    return read(token, () -> {
      EditorWorkspace.View view = api.parts().workspace().view();
      String hash = "sha256:" + hex;
      EditorWorkspace.Blob blob = visibleImageHashes(view, token).contains(hash) ? view.blobs().get(hash) : null;
      if (blob == null) {
        throw new EditorError(EditorError.Code.NOT_FOUND, "No image " + hash + ".");
      }
      Map<String, String> headers = Map.of("ETag", "\"" + hex + "\"",
          "Cache-Control", "private, max-age=31536000, immutable");
      if (request.header("If-None-Match").map((String tag) -> tag.contains(hex)).orElse(false)) {
        return new EditorResponse(304, headers, Optional.empty());
      }
      return new EditorResponse(200, headers, Optional.of(blob(blob)));
    });
  }

  CompletionStage<EditorResponse> meta(EditorRequest request, EditorTokens.Token token, String[] segments)
      throws EditorError {
    return read(token, () -> {
      String hash = api.parts().workspace().metaHash();
      return section(request, hash, "metaHash", "meta", api.parts().workspace().meta());
    });
  }

  CompletionStage<EditorResponse> notes(EditorRequest request, EditorTokens.Token token, String[] segments)
      throws EditorError {
    return api.files(token, () -> {
      JsonObject notes = api.parts().workspace().notes();
      String hash = api.parts().workspace().view().notesHash();
      if (!token.folders().isEmpty()) {
        notes = filterNotes(notes, token);
      }
      return section(request, hash, "notesHash", "notes", notes);
    });
  }

  CompletionStage<EditorResponse> history(EditorRequest request, EditorTokens.Token token, String[] segments)
      throws EditorError {
    Map<String, String> query = EditorReplies.query(request);
    unknown(query, Set.of("kind", "id", "version"));
    String kind = query.getOrDefault("kind", "");
    String id = query.getOrDefault("id", "");
    long version;
    try {
      version = Long.parseLong(query.getOrDefault("version", ""));
    } catch (NumberFormatException invalid) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, "version must be a number.");
    }
    if (!KIND.matcher(kind).matches() || id.isEmpty() || id.length() > 256) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, "kind and id name a history row.");
    }
    if (!token.reaches(EditorEvents.folder(id))) {
      throw EditorApi.folderDenied(token, kind + " " + id);
    }
    return api.files(token, () -> {
      Optional<String> json = api.parts().workspace().history(kind, id, version);
      JsonObject answer = new JsonObject();
      answer.addProperty("kind", kind);
      answer.addProperty("id", id);
      answer.addProperty("version", version);
      answer.addProperty("found", json.isPresent());
      if (json.isPresent()) {
        answer.addProperty("json", json.get());
      } else {
        answer.add("json", JsonNull.INSTANCE);
      }
      return EditorResponse.json(200, answer);
    });
  }

  private static EditorResponse section(EditorRequest request, String hash, String hashField, String field,
                                        JsonObject value) {
    Map<String, String> headers = Map.of("ETag", "\"" + hash + "\"", "Cache-Control", "no-cache");
    if (request.header("If-None-Match").map((String tag) -> tag.equals("\"" + hash + "\"")).orElse(false)) {
      return new EditorResponse(304, headers, Optional.empty());
    }
    JsonObject body = new JsonObject();
    body.addProperty(hashField, hash);
    body.add(field, value);
    return new EditorResponse(200, headers, Optional.of(body));
  }

  private static JsonObject filterNotes(JsonObject notes, EditorTokens.Token token) {
    JsonObject filtered = notes.deepCopy();
    JsonArray warnings = new JsonArray();
    for (JsonElement warning : notes.getAsJsonArray("warnings")) {
      String[] parts = warning.getAsString().split("\\|", -1);
      if (parts.length >= 3 && !token.reaches(EditorEvents.folder(parts[2]))) {
        continue;
      }
      warnings.add(warning);
    }
    filtered.add("warnings", warnings);
    JsonArray history = new JsonArray();
    for (JsonElement row : notes.getAsJsonArray("history")) {
      if (token.reaches(EditorEvents.folder(row.getAsJsonObject().get("id").getAsString()))) {
        history.add(row);
      }
    }
    filtered.add("history", history);
    return filtered;
  }

  /**
   * The image hashes the token sees in this catalog: every image without folders, else the set built once per
   * catalog and folder list, so image requests do not walk the catalog each time.
   */
  private Set<String> visibleImageHashes(EditorWorkspace.View view, EditorTokens.Token token) {
    if (token.folders().isEmpty()) {
      return view.blobs().keySet();
    }
    VisibleImages cached = visibleImages.get(token.folders());
    if (cached != null && cached.view() == view) {
      return cached.hashes();
    }
    Set<String> visible = new HashSet<>();
    for (EditorWorkspace.ImageRow row : view.images()) {
      if (token.reaches(row.folder())) {
        visible.add(row.hash());
      }
    }
    if (visibleImages.size() >= EditorTokens.MAXIMUM_TOKENS) {
      visibleImages.clear();
    }
    Set<String> hashes = Set.copyOf(visible);
    visibleImages.put(token.folders(), new VisibleImages(view, hashes));
    return hashes;
  }

  private static JsonObject blob(EditorWorkspace.Blob blob) {
    JsonObject json = new JsonObject();
    json.addProperty("hash", blob.hash());
    json.addProperty("mediaType", blob.mediaType());
    json.addProperty("data", Base64.getEncoder().encodeToString(blob.bytes()));
    return json;
  }

  private static JsonObject body(EditorWorkspace.DocumentRow row, String json) {
    JsonObject body = new JsonObject();
    body.addProperty("kind", row.kind());
    body.addProperty("id", row.id());
    body.addProperty("hash", row.hash());
    if (row.revision() != null) {
      body.addProperty("revision", row.revision());
    }
    body.addProperty("json", json);
    return body;
  }

  private static JsonObject error(String kind, String id, String code, String message) {
    JsonObject error = new JsonObject();
    error.addProperty("kind", kind);
    error.addProperty("id", id);
    error.addProperty("code", code);
    error.addProperty("message", message);
    return error;
  }

  private static String text(JsonObject object, String field) throws EditorError {
    JsonElement value = object.get(field);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, field + " must be text.");
    }
    return value.getAsString();
  }

  private static String decode(String segment) throws EditorError {
    try {
      return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException invalid) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, "The path is not URL encoded.");
    }
  }

  private static void unknown(Map<String, String> query, Set<String> allowed) throws EditorError {
    for (String key : query.keySet()) {
      if (!allowed.contains(key)) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "Unknown query parameter " + key + ".");
      }
    }
  }

  private static Optional<String> cursor(String raw) throws EditorError {
    if (raw == null || raw.isEmpty()) {
      return Optional.empty();
    }
    if (!CURSOR.matcher(raw).matches()) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, "after is not a cursor this server gave.");
    }
    try {
      String key = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
      if (!key.startsWith("d\n") && !key.startsWith("i\n")) {
        throw new IllegalArgumentException("unknown cursor");
      }
      return Optional.of(key);
    } catch (IllegalArgumentException invalid) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, "after is not a cursor this server gave.");
    }
  }

  private static void putCursor(JsonObject body, String key) {
    if (key == null) {
      body.add("next", JsonNull.INSTANCE);
    } else {
      body.addProperty("next", Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8)));
    }
  }

  /** Orders cursor keys as the catalog lists them: documents by kind then id, then images by path. */
  static int compare(String left, String right) {
    char leftSection = left.charAt(0);
    char rightSection = right.charAt(0);
    if (leftSection != rightSection) {
      return Character.compare(leftSection, rightSection);
    }
    if (leftSection == 'i') {
      return left.substring(2).compareTo(right.substring(2));
    }
    String[] leftParts = left.split("\n", 3);
    String[] rightParts = right.split("\n", 3);
    int kind = leftParts[1].compareTo(rightParts[1]);
    if (kind != 0) {
      return kind;
    }
    return (leftParts.length > 2 ? leftParts[2] : "").compareTo(rightParts.length > 2 ? rightParts[2] : "");
  }

  private static int size(JsonObject json) {
    return json.toString().getBytes(StandardCharsets.UTF_8).length;
  }
}
