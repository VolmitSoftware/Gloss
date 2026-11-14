package art.arcane.gloss.editor.sync;

import art.arcane.gloss.doc.ShippedDefaults;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Turns a changeset into the full project the validators and the apply path understand: the server's current
 * project with every non-conflicting change laid over it. Each entry is judged on its own base hash, so one moved
 * document or image never blocks the rest. A put whose content the server already holds counts as landed, so a
 * retry after a lost answer is not a conflict. A file the server could not read is a conflict for any entry that
 * names it, and deleting a document Gloss ships resets it to the shipped copy.
 */
final class EditorSyncChangesetMerger {
  private EditorSyncChangesetMerger() {
  }

  /** {@code restored}: keys of shipped documents a delete reset to their shipped copy. */
  record Merged(JsonObject project, List<EditorSyncConflict> conflicts, int changes, Set<String> restored) {
    Merged {
      conflicts = List.copyOf(conflicts);
      restored = Set.copyOf(restored);
    }
  }

  /**
   * Merges {@code changeset} onto {@code current}. {@code blobs} holds the bytes of every image put, keyed by hash;
   * {@code unreadable} and {@code unreadableImages} name the files the server holds but could not read. Throws
   * {@link IllegalArgumentException} naming the entry for a change this server cannot take at all.
   */
  static Merged merge(JsonObject current, EditorSyncChangeset changeset, Map<String, byte[]> blobs,
                      Set<String> unreadable, Set<String> unreadableImages) {
    Map<String, EditorSyncDocuments.Entry> documents = new TreeMap<>();
    for (EditorSyncDocuments.Entry entry : EditorSyncDocuments.parse(current)) {
      String hash = EditorSyncDocuments.contentRevision(entry.json());
      documents.put(key(entry.kind(), entry.id()), entry.withBaseRevision(hash));
    }
    List<EditorSyncConflict> conflicts = new ArrayList<>();
    Set<String> restored = new TreeSet<>();
    int changes = 0;
    for (EditorSyncChangeset.DocumentChange change : changeset.documents()) {
      EditorSyncDocumentKind kind = EditorSyncDocuments.handledKind(change.kind());
      if (kind == null) {
        throw new IllegalArgumentException("document kind is not handled by this server: " + change.kind() + " "
            + change.id());
      }
      String key = key(change.kind(), change.id());
      EditorSyncDocuments.Entry existing = documents.get(key);
      String name = EditorWorkspace.name(change.id(), existing == null
          ? (change.json() == null ? "{}" : change.json()) : existing.json());
      if (unreadable.contains(key)) {
        conflicts.add(new EditorSyncConflict(change.kind(), change.id(), name, "changed"));
        continue;
      }
      if (change.delete()) {
        if (existing == null) {
          continue;
        }
        if (!existing.baseRevision().equals(change.baseHash())) {
          conflicts.add(new EditorSyncConflict(change.kind(), change.id(), name, "changed"));
          continue;
        }
        Optional<byte[]> stock = ShippedDefaults.shippedCopy(kind.layout() == EditorSyncDocumentKind.Layout.SINGLE
            ? kind.storageName() : kind.storageName() + "/" + change.id() + ".json");
        if (stock.isPresent()) {
          restored.add(key);
          String reset = withRevision(kind, new String(stock.get(), StandardCharsets.UTF_8), existing.revision());
          if (!sameContent(kind, existing.json(), reset)) {
            documents.put(key, new EditorSyncDocuments.Entry(change.kind(), change.id(), existing.revision(), reset,
                existing.baseRevision()));
            changes++;
          }
          continue;
        }
        documents.remove(key);
        changes++;
        continue;
      }
      if (existing != null && sameContent(kind, existing.json(), change.json())) {
        continue;
      }
      if (existing == null && change.baseHash() != null) {
        conflicts.add(new EditorSyncConflict(change.kind(), change.id(), name, "deleted"));
        continue;
      }
      if (existing != null && change.baseHash() == null) {
        conflicts.add(new EditorSyncConflict(change.kind(), change.id(), name, "exists"));
        continue;
      }
      if (existing != null && !existing.baseRevision().equals(change.baseHash())) {
        conflicts.add(new EditorSyncConflict(change.kind(), change.id(), name, "changed"));
        continue;
      }
      Long revision = kind.versioned() ? revisionOf(change) : null;
      documents.put(key, new EditorSyncDocuments.Entry(change.kind(), change.id(), revision, change.json(),
          existing == null ? null : existing.baseRevision()));
      changes++;
    }

    Map<String, String> images = new TreeMap<>();
    Map<String, String> imageHashes = new LinkedHashMap<>();
    for (JsonElement value : EditorSyncJson.requireArray(current, "images")) {
      JsonObject image = value.getAsJsonObject();
      String path = EditorSyncJson.requireString(image, "path");
      String data = EditorSyncJson.requireString(image, "data");
      images.put(path, data);
      imageHashes.put(path, EditorWorkspace.sha256(dataBytes(data)));
    }
    for (EditorSyncChangeset.ImageChange change : changeset.images()) {
      String path = EditorSyncSnapshotBuilder.normalizeRelative(change.path());
      String existing = imageHashes.get(path);
      if (unreadableImages.contains(path)) {
        conflicts.add(imageConflict(path, "changed"));
        continue;
      }
      if (change.delete()) {
        if (existing == null) {
          continue;
        }
        if (!existing.equals(change.baseHash())) {
          conflicts.add(imageConflict(path, "changed"));
          continue;
        }
        images.remove(path);
        changes++;
        continue;
      }
      if (existing != null && existing.equals(change.hash())) {
        continue;
      }
      if (existing == null && change.baseHash() != null) {
        conflicts.add(imageConflict(path, "deleted"));
        continue;
      }
      if (existing != null && change.baseHash() == null) {
        conflicts.add(imageConflict(path, "exists"));
        continue;
      }
      if (existing != null && !existing.equals(change.baseHash())) {
        conflicts.add(imageConflict(path, "changed"));
        continue;
      }
      byte[] bytes = blobs.get(change.hash());
      if (bytes == null) {
        throw new IllegalArgumentException("image bytes are missing: " + path);
      }
      String mediaType = EditorSyncSnapshotBuilder.detectMediaType(bytes);
      images.put(path, "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes));
      changes++;
    }

    JsonObject project = current.deepCopy();
    project.remove("baseRevision");
    List<EditorSyncDocuments.Entry> ordered = new ArrayList<>(documents.values());
    ordered.sort(Comparator.comparing(EditorSyncDocuments.Entry::kind).thenComparing(EditorSyncDocuments.Entry::id));
    project.add("documents", EditorSyncDocuments.build(ordered));
    JsonArray imageArray = new JsonArray();
    for (Map.Entry<String, String> image : images.entrySet()) {
      JsonObject value = new JsonObject();
      value.addProperty("path", image.getKey());
      value.addProperty("data", image.getValue());
      imageArray.add(value);
    }
    project.add("images", imageArray);
    project.addProperty("baseRevision", EditorSyncJson.revision(project));
    conflicts.sort(Comparator.comparing(EditorSyncConflict::kind).thenComparing(EditorSyncConflict::id));
    return new Merged(project, conflicts, changes, restored);
  }

  /** The shipped source with the server's revision, which a put must carry; unversioned kinds keep it as is. */
  private static String withRevision(EditorSyncDocumentKind kind, String source, Long revision) {
    if (!kind.versioned() || revision == null) {
      return source;
    }
    JsonObject object = JsonParser.parseString(source).getAsJsonObject();
    object.addProperty("revision", revision);
    return object.toString();
  }

  static String key(String kind, String id) {
    return kind + '\0' + id;
  }

  /** The bytes of a {@code data:} URL. */
  static byte[] dataBytes(String dataUrl) {
    int comma = dataUrl.indexOf(',');
    if (!dataUrl.startsWith("data:") || comma < 6) {
      throw new IllegalArgumentException("image data must be a data URL");
    }
    return Base64.getDecoder().decode(dataUrl.substring(comma + 1));
  }

  /**
   * True when {@code incoming} holds what the server already has: the same canonical JSON, ignoring the
   * server-owned {@code revision} of versioned kinds.
   */
  private static boolean sameContent(EditorSyncDocumentKind kind, String existing, String incoming) {
    try {
      JsonElement left = JsonParser.parseString(existing);
      JsonElement right = JsonParser.parseString(incoming);
      if (kind.versioned() && left.isJsonObject() && right.isJsonObject()) {
        JsonObject leftObject = left.getAsJsonObject().deepCopy();
        JsonObject rightObject = right.getAsJsonObject().deepCopy();
        leftObject.remove("revision");
        rightObject.remove("revision");
        return EditorSyncJson.canonical(leftObject).equals(EditorSyncJson.canonical(rightObject));
      }
      return EditorSyncJson.canonical(left).equals(EditorSyncJson.canonical(right));
    } catch (JsonParseException malformed) {
      return false;
    }
  }

  private static Long revisionOf(EditorSyncChangeset.DocumentChange change) {
    try {
      JsonElement parsed = JsonParser.parseString(change.json());
      JsonElement revision = parsed.isJsonObject() ? parsed.getAsJsonObject().get("revision") : null;
      if (revision == null || !revision.isJsonPrimitive() || !revision.getAsJsonPrimitive().isNumber()) {
        throw new IllegalArgumentException(change.kind() + " " + change.id() + ": revision must be a number");
      }
      return revision.getAsLong();
    } catch (JsonParseException malformed) {
      throw new IllegalArgumentException(change.kind() + " " + change.id() + ": the document is not valid JSON",
          malformed);
    }
  }

  private static EditorSyncConflict imageConflict(String path, String reason) {
    return new EditorSyncConflict(EditorSyncConflict.IMAGE_KIND, path, path, reason);
  }
}
