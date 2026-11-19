package art.arcane.gloss.editor;

import art.arcane.gloss.editor.sync.EditorSyncChangeset;
import art.arcane.gloss.editor.sync.EditorSyncConflict;
import art.arcane.gloss.editor.sync.EditorWorkspace;
import art.arcane.volmlib.util.editor.EditorTokens;
import art.arcane.volmlib.util.editor.transport.EditorRequest;
import art.arcane.volmlib.util.editor.transport.EditorResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/**
 * {@code POST /v1/images} (stage an upload) and {@code POST /v1/changes} (publish). A publish is checked for scopes
 * and folders before anything is read, runs on the file lane one at a time, and answers with the rows of the entries
 * it named; what else the files showed goes out as the server's catalog change. Its answer is kept by idempotency key,
 * and a retry gets it back before anything else is checked, so a retry after a lost answer applies nothing twice.
 */
final class EditorPublishRoutes {
  static final int MAXIMUM_DOCUMENT_ENTRIES = 2_000;
  static final int MAXIMUM_IMAGE_ENTRIES = 4_096;
  /** A publish deleting more than this many documents and images, and more than this share, needs a confirmation. */
  static final int CONFIRM_DELETES_ABOVE = 25;
  static final int CONFIRM_DELETES_PERCENT = 20;
  /** Deletions one token publishes within this window count together against the confirmation threshold. */
  static final Duration CONFIRM_DELETES_WINDOW = Duration.ofMinutes(10);
  static final Duration IDEMPOTENCY = Duration.ofHours(24);
  private static final int IDEMPOTENCY_KEYS = 256;
  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{16,64}");
  private static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9-]{0,31}");
  private static final Set<String> MEDIA_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp");

  private record Answer(Instant at, String tokenId, JsonObject body) {
  }

  private record Deletions(Instant at, long count) {
  }

  private final EditorApi api;
  private final Map<String, Answer> answers = new LinkedHashMap<>();
  private final Map<String, Deque<Deletions>> deletions = new HashMap<>();

  EditorPublishRoutes(EditorApi api) {
    this.api = api;
  }

  CompletionStage<EditorResponse> upload(EditorRequest request, EditorTokens.Token<EditorScope> token, String[] segments)
      throws EditorError {
    JsonObject body = EditorApi.body(request);
    EditorApi.fields(body, Set.of("mediaType", "data"));
    String mediaType = null;
    if (body.has("mediaType")) {
      JsonElement value = body.get("mediaType");
      if (!value.isJsonPrimitive() || !MEDIA_TYPES.contains(value.getAsString())) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "unsupported image type; use png, jpeg, gif, webp or bmp.");
      }
      mediaType = value.getAsString();
    }
    JsonElement data = body.get("data");
    if (data == null || !data.isJsonPrimitive() || !data.getAsJsonPrimitive().isString()) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, "data must be the image bytes in base64.");
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(data.getAsString());
    } catch (IllegalArgumentException invalid) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, "data is not base64.");
    }
    EditorWorkspace.ImageRow image;
    try {
      image = EditorWorkspace.validateUpload(mediaType, bytes);
    } catch (IllegalArgumentException invalid) {
      EditorError.Code code = bytes.length > 512 * 1024 ? EditorError.Code.TOO_LARGE : EditorError.Code.INVALID_REQUEST;
      throw new EditorError(code, invalid.getMessage() + ".");
    }
    boolean known = api.parts().workspace().view().blobs().containsKey(image.hash());
    boolean fresh = api.parts().uploads().put(image.hash(), image.mediaType(), bytes);
    JsonObject answer = new JsonObject();
    answer.addProperty("hash", image.hash());
    answer.addProperty("size", image.size());
    answer.addProperty("mediaType", image.mediaType());
    answer.addProperty("width", image.width());
    answer.addProperty("height", image.height());
    return EditorApi.done(EditorResponse.json(fresh && !known ? 201 : 200, answer));
  }

  CompletionStage<EditorResponse> publish(EditorRequest request, EditorTokens.Token<EditorScope> token, String[] segments)
      throws EditorError {
    JsonObject body = EditorApi.body(request);
    EditorApi.fields(body, Set.of("idempotencyKey", "documents", "images", "confirmDeletes"));
    String key = EditorApi.string(body, "idempotencyKey", KEY);
    Optional<JsonObject> answered = earlier(key, token.id());
    if (answered.isPresent()) {
      return EditorApi.done(EditorResponse.json(200, answered.get()));
    }
    List<EditorSyncChangeset.DocumentChange> documents = documents(body);
    List<EditorSyncChangeset.ImageChange> images = images(body);
    long confirmed = confirmed(body);
    if (documents.isEmpty() && images.isEmpty()) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, "The publish names no document or image.");
    }
    allowed(token, documents, images);
    EditorWorkspace.View view = api.parts().workspace().view();
    confirmDeletes(token, documents, images, view, confirmed);
    Map<String, byte[]> blobs = new LinkedHashMap<>();
    JsonArray missing = new JsonArray();
    for (EditorSyncChangeset.ImageChange image : images) {
      if (image.delete() || blobs.containsKey(image.hash())) {
        continue;
      }
      Optional<EditorUploads.Staged> staged = api.parts().uploads().get(image.hash());
      if (staged.isPresent()) {
        blobs.put(image.hash(), staged.get().bytes());
      } else if (view.blobs().containsKey(image.hash())) {
        blobs.put(image.hash(), view.blobs().get(image.hash()).bytes());
      } else {
        missing.add(image.hash());
      }
    }
    if (!missing.isEmpty()) {
      JsonObject extra = new JsonObject();
      extra.add("missing", missing);
      throw new EditorError(EditorError.Code.INVALID_REQUEST, "Upload these images with POST /v1/images first.", -1L,
          extra);
    }
    EditorSyncChangeset changeset = new EditorSyncChangeset(documents, images);
    return api.files(token, () -> {
      Optional<JsonObject> earlier = earlier(key, token.id());
      if (earlier.isPresent()) {
        return EditorResponse.json(200, earlier.get());
      }
      String changeId = "chg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
      EditorWorkspace.Outcome outcome = api.parts().workspace().publish(changeId, changeset, blobs);
      JsonObject answer = answer(changeId, key, outcome);
      remember(key, token.id(), answer);
      if (outcome.others().contentChanged()) {
        api.parts().events().publish(EditorCatalogEvents.catalog(outcome.others(), EditorWorkspace.Source.SERVER));
      }
      if (outcome.delta().contentChanged()) {
        JsonObject change = new JsonObject();
        change.addProperty("type", "change");
        change.add("result", answer.deepCopy());
        api.parts().events().publish(change);
        api.parts().events().publish(EditorCatalogEvents.catalog(outcome.delta(), EditorWorkspace.Source.EDITOR));
      }
      if (!"rejected".equals(outcome.status()) || outcome.delta().contentChanged()) {
        api.parts().notices().published(token, outcome.message());
      } else {
        api.parts().host().log("Web editor publish by " + EditorNotices.name(token.id(), token.label()) + ": "
            + outcome.message());
      }
      return EditorResponse.json(200, answer);
    });
  }

  /**
   * Every entry needs its scope and folder; a put that runs console commands, or a behavior that runs any command,
   * also needs publish:commands.
   */
  private static void allowed(EditorTokens.Token<EditorScope> token, List<EditorSyncChangeset.DocumentChange> documents,
                              List<EditorSyncChangeset.ImageChange> images) throws EditorError {
    List<String> commands = new ArrayList<>();
    for (EditorSyncChangeset.DocumentChange change : documents) {
      EditorScope scope = EditorScope.forKind(change.kind()).orElseThrow(() -> new EditorError(
          EditorError.Code.INVALID_REQUEST, "This server does not publish " + change.kind() + " documents."));
      if (!token.has(scope)) {
        throw EditorApi.missing(scope);
      }
      if (!token.reaches(EditorEvents.folder(change.id()))) {
        throw EditorApi.folderDenied(token, change.kind() + " " + change.id());
      }
      if (!change.delete() && EditorWorkspace.runsCommands(change.kind(), parse(change))) {
        commands.add(change.kind() + " " + change.id());
      }
    }
    if (!commands.isEmpty() && !token.has(EditorScope.PUBLISH_COMMANDS)) {
      JsonObject extra = new JsonObject();
      extra.addProperty("scope", EditorScope.PUBLISH_COMMANDS.wireName());
      extra.add("documents", EditorApi.strings(commands));
      throw new EditorError(EditorError.Code.SCOPE_MISSING, "This token lacks publish:commands; these documents run "
          + "commands as the console or from a behavior: "
          + String.join(", ", commands.subList(0, Math.min(5, commands.size())))
          + (commands.size() > 5 ? " and " + (commands.size() - 5) + " more" : "") + ".", -1L, extra);
    }
    for (EditorSyncChangeset.ImageChange change : images) {
      if (!token.has(EditorScope.PUBLISH_IMAGES)) {
        throw EditorApi.missing(EditorScope.PUBLISH_IMAGES);
      }
      if (!token.reaches(EditorEvents.folder(change.path()))) {
        throw EditorApi.folderDenied(token, "image " + change.path());
      }
    }
  }

  /** {@code confirmDeletes}: the deletion count the user agreed to, or -1 when absent. */
  private static long confirmed(JsonObject body) throws EditorError {
    JsonElement value = body.get("confirmDeletes");
    if (value == null) {
      return -1L;
    }
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, "confirmDeletes is the number of deletions.");
    }
    try {
      long count = value.getAsJsonPrimitive().getAsBigDecimal().longValueExact();
      if (count < 0) {
        throw new ArithmeticException("negative");
      }
      return count;
    } catch (ArithmeticException invalid) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, "confirmDeletes is the number of deletions.");
    }
  }

  /**
   * Refuses deleting more than {@link #CONFIRM_DELETES_ABOVE} documents and images and more than
   * {@link #CONFIRM_DELETES_PERCENT} percent of those the token may publish, unless {@code confirmDeletes} names that
   * count, so a browser that lost its copy of the workspace cannot empty the server with one click. Deletions the
   * token published within {@link #CONFIRM_DELETES_WINDOW} count too, so splitting them into small publishes does
   * not get around it; a confirmation starts the count again.
   */
  private synchronized void confirmDeletes(EditorTokens.Token<EditorScope> token, List<EditorSyncChangeset.DocumentChange> documents,
                                           List<EditorSyncChangeset.ImageChange> images, EditorWorkspace.View view,
                                           long confirmed) throws EditorError {
    long deletes = documents.stream().filter(EditorSyncChangeset.DocumentChange::delete).count()
        + images.stream().filter(EditorSyncChangeset.ImageChange::delete).count();
    if (deletes == 0) {
      return;
    }
    Instant now = api.parts().clock().instant();
    Deque<Deletions> recent = deletions.computeIfAbsent(token.id(), (String id) -> new ArrayDeque<>());
    while (!recent.isEmpty() && recent.peekFirst().at().isBefore(now.minus(CONFIRM_DELETES_WINDOW))) {
      recent.pollFirst();
    }
    long earlier = recent.stream().mapToLong(Deletions::count).sum();
    long burst = earlier + deletes;
    if (confirmed == deletes || confirmed == burst) {
      recent.clear();
      return;
    }
    long total = earlier + view.documents().stream().filter((EditorWorkspace.DocumentRow row) ->
        token.reaches(row.folder()) && EditorScope.forKind(row.kind()).map(token::has).orElse(false)).count()
        + (token.has(EditorScope.PUBLISH_IMAGES) ? view.images().stream()
        .filter((EditorWorkspace.ImageRow row) -> token.reaches(row.folder())).count() : 0L);
    if (burst > CONFIRM_DELETES_ABOVE && burst * 100L > total * CONFIRM_DELETES_PERCENT) {
      JsonObject extra = new JsonObject();
      extra.addProperty("deletes", burst);
      extra.addProperty("total", total);
      throw new EditorError(EditorError.Code.CONFIRM_DELETES, "Publishing would delete " + burst + " of the " + total
          + " documents and images " + (token.folders().isEmpty() ? "on this server" : "this pairing reaches")
          + ". Nothing was published.", -1L, extra);
    }
    recent.addLast(new Deletions(now, deletes));
  }

  private static JsonElement parse(EditorSyncChangeset.DocumentChange change) throws EditorError {
    try {
      return JsonParser.parseString(change.json());
    } catch (JsonParseException invalid) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, change.kind() + " " + change.id()
          + ": the document is not valid JSON.");
    }
  }

  private static List<EditorSyncChangeset.DocumentChange> documents(JsonObject body) throws EditorError {
    List<EditorSyncChangeset.DocumentChange> changes = new ArrayList<>();
    JsonElement value = body.get("documents");
    if (value == null) {
      return changes;
    }
    if (!value.isJsonArray() || value.getAsJsonArray().size() > MAXIMUM_DOCUMENT_ENTRIES) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, "documents holds at most " + MAXIMUM_DOCUMENT_ENTRIES
          + " entries.");
    }
    Set<String> seen = new HashSet<>();
    for (JsonElement element : value.getAsJsonArray()) {
      if (!element.isJsonObject()) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "Each document entry is an object.");
      }
      JsonObject entry = element.getAsJsonObject();
      String op = text(entry, "op");
      boolean delete = op.equals("delete");
      if (!delete && !op.equals("put")) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "op is put or delete.");
      }
      EditorApi.fields(entry, delete ? Set.of("op", "kind", "id", "baseHash") : Set.of("op", "kind", "id", "baseHash", "json"));
      String kind = text(entry, "kind");
      String id = text(entry, "id");
      if (!KIND.matcher(kind).matches() || id.isEmpty() || id.length() > 256) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "kind and id name a document.");
      }
      if (!seen.add(kind + "\n" + id)) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, kind + " " + id + " appears twice.");
      }
      String baseHash = hash(entry, "baseHash");
      if (delete && baseHash == null) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "A delete needs the baseHash it removes.");
      }
      changes.add(new EditorSyncChangeset.DocumentChange(delete, kind, id, baseHash, delete ? null : text(entry, "json")));
    }
    return changes;
  }

  private static List<EditorSyncChangeset.ImageChange> images(JsonObject body) throws EditorError {
    List<EditorSyncChangeset.ImageChange> changes = new ArrayList<>();
    JsonElement value = body.get("images");
    if (value == null) {
      return changes;
    }
    if (!value.isJsonArray() || value.getAsJsonArray().size() > MAXIMUM_IMAGE_ENTRIES) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, "images holds at most " + MAXIMUM_IMAGE_ENTRIES + " entries.");
    }
    Set<String> seen = new LinkedHashSet<>();
    for (JsonElement element : value.getAsJsonArray()) {
      if (!element.isJsonObject()) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "Each image entry is an object.");
      }
      JsonObject entry = element.getAsJsonObject();
      String op = text(entry, "op");
      boolean delete = op.equals("delete");
      if (!delete && !op.equals("put")) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "op is put or delete.");
      }
      EditorApi.fields(entry, delete ? Set.of("op", "path", "baseHash")
          : Set.of("op", "path", "baseHash", "hash", "mediaType"));
      String path = text(entry, "path");
      if (path.isEmpty() || path.length() > 256 || !seen.add(path)) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "Each image path appears once and is 1 to 256 characters.");
      }
      if (!EditorWorkspace.imagePath(path).map(path::equals).orElse(false)) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "Image path " + path + " is not one the server stores: "
            + "folders are separated by / and no part is empty, hidden or padded with spaces.");
      }
      String baseHash = hash(entry, "baseHash");
      String hash = delete ? null : hash(entry, "hash");
      if (!delete && hash == null) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "An image put needs the hash of uploaded bytes.");
      }
      if (delete && baseHash == null) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "A delete needs the baseHash it removes.");
      }
      String mediaType = delete || !entry.has("mediaType") ? null : text(entry, "mediaType");
      changes.add(new EditorSyncChangeset.ImageChange(delete, path, baseHash, hash, mediaType));
    }
    return changes;
  }

  private static String text(JsonObject object, String field) throws EditorError {
    JsonElement value = object.get(field);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, field + " must be text.");
    }
    return value.getAsString();
  }

  private static String hash(JsonObject object, String field) throws EditorError {
    JsonElement value = object.get(field);
    if (value == null || value.isJsonNull()) {
      return null;
    }
    if (!value.isJsonPrimitive() || !HASH.matcher(value.getAsString()).matches()) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, field + " is sha256:<64 hex> or null.");
    }
    return value.getAsString();
  }

  private static JsonObject answer(String changeId, String key, EditorWorkspace.Outcome outcome) {
    JsonObject answer = new JsonObject();
    answer.addProperty("changeId", changeId);
    answer.addProperty("idempotencyKey", key);
    answer.addProperty("status", outcome.status());
    answer.addProperty("message", outcome.message());
    JsonArray conflicts = new JsonArray();
    for (EditorSyncConflict conflict : outcome.conflicts()) {
      conflicts.add(conflict.json());
    }
    answer.add("conflicts", conflicts);
    JsonObject delta = EditorCatalogEvents.delta(outcome.delta(), null);
    for (String field : List.of("documents", "deleted", "images", "removedImages")) {
      answer.add(field, delta.has(field) ? delta.get(field) : new JsonArray());
    }
    return answer;
  }

  private synchronized Optional<JsonObject> earlier(String key, String tokenId) {
    prune();
    Answer answer = answers.get(key);
    return answer == null || !answer.tokenId().equals(tokenId) ? Optional.empty() : Optional.of(answer.body().deepCopy());
  }

  private synchronized void remember(String key, String tokenId, JsonObject body) {
    prune();
    answers.put(key, new Answer(api.parts().clock().instant(), tokenId, body.deepCopy()));
    Iterator<String> oldest = answers.keySet().iterator();
    while (answers.size() > IDEMPOTENCY_KEYS && oldest.hasNext()) {
      oldest.next();
      oldest.remove();
    }
  }

  private void prune() {
    Instant cutoff = api.parts().clock().instant().minus(IDEMPOTENCY);
    answers.values().removeIf((Answer answer) -> answer.at().isBefore(cutoff));
  }
}
