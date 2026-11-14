package art.arcane.gloss.editor;

import art.arcane.gloss.editor.transport.EditorEventSink;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Event streams of {@code GET /v1/events}. Each stream sends at most {@code framesPerSecond} frames; queued
 * catalog changes merge per document and image and go out in frames of at most {@link #CATALOG_FRAME_BYTES}
 * of entries; a stream that falls {@link #QUEUE_LIMIT} frames behind is closed with reason {@code limit}.
 * {@link #tick} drains one frame per stream and is called by the editor service at the frame rate.
 */
public final class EditorEvents {
  public static final int QUEUE_LIMIT = 256;
  /** The most entry bytes one catalog frame carries, so a frame fits the smallest relay frame limit. */
  public static final int CATALOG_FRAME_BYTES = 48 * 1024;

  /** One authenticated stream. {@code reaches} filters entries by the token's folders. */
  private static final class Stream {
    final String tokenId;
    final EditorEventSink sink;
    final Predicate<String> reaches;
    final Deque<JsonObject> queue = new ArrayDeque<>();
    /** Catalog changes waiting to be sent, by key: a newer version or a removal replaces the older one. */
    final Map<String, JsonObject> documents = new LinkedHashMap<>();
    final Map<String, JsonObject> deleted = new LinkedHashMap<>();
    final Map<String, JsonObject> images = new LinkedHashMap<>();
    final Map<String, JsonObject> removedImages = new LinkedHashMap<>();
    final Map<String, String> sections = new LinkedHashMap<>();

    Stream(String tokenId, EditorEventSink sink, Predicate<String> reaches) {
      this.tokenId = tokenId;
      this.sink = sink;
      this.reaches = reaches;
    }

    boolean catalogWaiting() {
      return !documents.isEmpty() || !deleted.isEmpty() || !images.isEmpty() || !removedImages.isEmpty()
          || !sections.isEmpty();
    }
  }

  private final List<Stream> streams = new ArrayList<>();

  /** Registers an authenticated stream; {@code first} is sent before anything else. Returns a remover. */
  public synchronized Runnable add(String tokenId, EditorEventSink sink, Predicate<String> reaches, JsonObject first) {
    Stream stream = new Stream(Objects.requireNonNull(tokenId), Objects.requireNonNull(sink),
        Objects.requireNonNull(reaches));
    stream.queue.addLast(first.deepCopy());
    streams.add(stream);
    return () -> remove(stream);
  }

  private synchronized void remove(Stream stream) {
    streams.remove(stream);
  }

  public synchronized int open() {
    return streams.size();
  }

  public synchronized int open(String tokenId) {
    int count = 0;
    for (Stream stream : streams) {
      if (stream.tokenId.equals(tokenId)) {
        count++;
      }
    }
    return count;
  }

  /**
   * Queues an event for every stream. Catalog frames merge, and catalog and change frames are filtered by each
   * stream's folders.
   */
  public synchronized void publish(JsonObject event) {
    String type = event.get("type").getAsString();
    for (Stream stream : new ArrayList<>(streams)) {
      if (type.equals("catalog")) {
        merge(stream, event);
        continue;
      }
      if (type.equals("change")) {
        JsonObject visible = visibleChange(event, stream.reaches);
        if (visible != null) {
          enqueue(stream, visible);
        }
        continue;
      }
      enqueue(stream, event.deepCopy());
    }
  }

  /** Sends a {@code closing} frame to the token's streams and closes them. */
  public void close(String tokenId, String reason) {
    closeWhere((Stream stream) -> stream.tokenId.equals(tokenId), reason);
  }

  public void closeAll(String reason) {
    closeWhere((Stream stream) -> true, reason);
  }

  private void closeWhere(Predicate<Stream> which, String reason) {
    List<Stream> closing = new ArrayList<>();
    synchronized (this) {
      for (Stream stream : streams) {
        if (which.test(stream)) {
          closing.add(stream);
        }
      }
      streams.removeAll(closing);
    }
    JsonObject frame = new JsonObject();
    frame.addProperty("type", "closing");
    frame.addProperty("reason", reason);
    for (Stream stream : closing) {
      stream.sink.send(frame.deepCopy());
      stream.sink.close();
    }
  }

  /** Sends at most one frame per stream: the oldest queued frame, else the next catalog frame. */
  public void tick() {
    List<Map.Entry<Stream, JsonObject>> sends = new ArrayList<>();
    synchronized (this) {
      for (Stream stream : streams) {
        JsonObject next = stream.queue.pollFirst();
        if (next == null) {
          next = catalogFrame(stream);
        }
        if (next != null) {
          sends.add(Map.entry(stream, next));
        }
      }
    }
    List<Stream> gone = new ArrayList<>();
    for (Map.Entry<Stream, JsonObject> send : sends) {
      if (!send.getKey().sink.send(send.getValue())) {
        gone.add(send.getKey());
      }
    }
    for (Stream stream : gone) {
      remove(stream);
    }
  }

  private void enqueue(Stream stream, JsonObject frame) {
    if (stream.queue.size() >= QUEUE_LIMIT) {
      Stream late = stream;
      Thread.ofVirtual().start(() -> closeWhere((Stream candidate) -> candidate == late, "limit"));
      return;
    }
    stream.queue.addLast(frame);
  }

  /** The folder of a document id or image path: everything before its last slash. */
  public static String folder(String idOrPath) {
    int slash = idOrPath.lastIndexOf('/');
    return slash < 0 ? "" : idOrPath.substring(0, slash);
  }

  /**
   * The change event with only the entries and conflicts in folders the stream reaches, or null when none of its
   * entries is. When anything was left out, the message goes too, since it can name what was left out.
   */
  private static JsonObject visibleChange(JsonObject event, Predicate<String> reaches) {
    JsonObject change = event.deepCopy();
    JsonObject result = change.getAsJsonObject("result");
    boolean hidden = false;
    boolean shown = false;
    for (String field : List.of("documents", "deleted", "images", "removedImages", "conflicts")) {
      if (!result.has(field)) {
        continue;
      }
      JsonArray kept = new JsonArray();
      for (JsonElement element : result.getAsJsonArray(field)) {
        JsonObject entry = element.getAsJsonObject();
        String key = entry.has("path") ? entry.get("path").getAsString() : entry.get("id").getAsString();
        if (reaches.test(folder(key))) {
          kept.add(entry);
        } else {
          hidden = true;
        }
      }
      result.add(field, kept);
      shown |= !field.equals("conflicts") && !kept.isEmpty();
    }
    if (!shown) {
      return null;
    }
    if (hidden) {
      result.remove("message");
    }
    return change;
  }

  /** Later versions replace earlier ones; a removal replaces a queued version and the other way round. */
  private static void merge(Stream stream, JsonObject event) {
    mergeInto(stream, event, "documents", "id", stream.documents, stream.deleted, true);
    mergeInto(stream, event, "deleted", "id", stream.deleted, stream.documents, true);
    mergeInto(stream, event, "images", "path", stream.images, stream.removedImages, false);
    mergeInto(stream, event, "removedImages", "path", stream.removedImages, stream.images, false);
    for (String section : List.of("metaHash", "notesHash")) {
      if (event.has(section)) {
        stream.sections.put(section, event.get(section).getAsString());
      }
    }
  }

  private static void mergeInto(Stream stream, JsonObject event, String field, String keyField,
                                Map<String, JsonObject> into, Map<String, JsonObject> opposite, boolean document) {
    if (!event.has(field)) {
      return;
    }
    for (JsonElement element : event.getAsJsonArray(field)) {
      JsonObject entry = element.getAsJsonObject();
      String keyValue = entry.get(keyField).getAsString();
      if (!stream.reaches.test(folder(keyValue))) {
        continue;
      }
      String key = document ? entry.get("kind").getAsString() + "\n" + keyValue : keyValue;
      opposite.remove(key);
      into.remove(key);
      into.put(key, entry.deepCopy());
    }
  }

  /** The oldest waiting catalog changes up to {@link #CATALOG_FRAME_BYTES}; at least one entry. Null when none wait. */
  private static JsonObject catalogFrame(Stream stream) {
    if (!stream.catalogWaiting()) {
      return null;
    }
    JsonObject frame = new JsonObject();
    frame.addProperty("type", "catalog");
    int[] bytes = {0};
    boolean full = drain(stream.documents, frame, "documents", bytes)
        || drain(stream.deleted, frame, "deleted", bytes)
        || drain(stream.images, frame, "images", bytes)
        || drain(stream.removedImages, frame, "removedImages", bytes);
    if (!full) {
      stream.sections.forEach(frame::addProperty);
      stream.sections.clear();
    }
    return frame;
  }

  /** Moves waiting entries into the frame; true when the frame filled before they ran out. */
  private static boolean drain(Map<String, JsonObject> waiting, JsonObject frame, String field, int[] bytes) {
    if (waiting.isEmpty()) {
      return false;
    }
    JsonArray into = new JsonArray();
    boolean full = false;
    for (Iterator<JsonObject> iterator = waiting.values().iterator(); iterator.hasNext(); ) {
      JsonObject entry = iterator.next();
      int size = entry.toString().getBytes(StandardCharsets.UTF_8).length;
      if (bytes[0] > 0 && bytes[0] + size > CATALOG_FRAME_BYTES) {
        full = true;
        break;
      }
      bytes[0] += size;
      into.add(entry);
      iterator.remove();
    }
    if (!into.isEmpty()) {
      frame.add(field, into);
    }
    return full;
  }
}
