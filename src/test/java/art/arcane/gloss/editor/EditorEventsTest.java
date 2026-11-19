package art.arcane.gloss.editor;

import art.arcane.volmlib.util.editor.transport.EditorEventSink;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorEventsTest {
  private static final class Sink implements EditorEventSink {
    final List<JsonObject> sent = new ArrayList<>();

    @Override
    public boolean send(JsonObject event) {
      sent.add(event);
      return true;
    }

    @Override
    public void close() {
    }
  }

  private static JsonObject open() {
    JsonObject open = new JsonObject();
    open.addProperty("type", "open");
    return open;
  }

  private static JsonObject catalog(String id, String hash, boolean deleted) {
    JsonObject event = new JsonObject();
    event.addProperty("type", "catalog");
    JsonObject entry = new JsonObject();
    entry.addProperty("kind", "menu");
    entry.addProperty("id", id);
    if (!deleted) {
      entry.addProperty("hash", hash);
    }
    JsonArray list = new JsonArray();
    list.add(entry);
    event.add(deleted ? "deleted" : "documents", list);
    return event;
  }

  @Test
  void queuedChangesMergePerDocumentAndRespectFolders() {
    EditorEvents events = new EditorEvents();
    Sink all = new Sink();
    Sink shop = new Sink();
    events.add("tok_a", all, (String folder) -> true, open());
    events.add("tok_b", shop, (String folder) -> folder.equals("shop"), open());

    events.publish(catalog("shop/main", "sha256:1", false));
    events.publish(catalog("shop/main", "sha256:2", false));
    events.publish(catalog("lobby/main", "sha256:3", false));
    events.publish(catalog("lobby/main", null, true));
    events.tick();
    events.tick();

    JsonObject frame = all.sent.get(1);
    assertEquals("open", all.sent.get(0).get("type").getAsString());
    assertEquals(1, frame.getAsJsonArray("documents").size());
    assertEquals("sha256:2", frame.getAsJsonArray("documents").get(0).getAsJsonObject().get("hash").getAsString());
    assertEquals("lobby/main", frame.getAsJsonArray("deleted").get(0).getAsJsonObject().get("id").getAsString());
    assertEquals(1, shop.sent.get(1).getAsJsonArray("documents").size());
    assertTrue(!shop.sent.get(1).has("deleted"));
  }

  @Test
  void aPublishResultShowsEachStreamOnlyWhatItsFoldersReach() {
    EditorEvents events = new EditorEvents();
    Sink all = new Sink();
    Sink shop = new Sink();
    events.add("tok_a", all, (String folder) -> true, open());
    events.add("tok_b", shop, (String folder) -> folder.equals("shop"), open());
    JsonObject result = new JsonObject();
    result.addProperty("status", "applied");
    result.addProperty("message", "Published 2 changes; 1 kept the server copy: Vault.");
    result.add("documents", entries("id", "shop/a", "secret/b"));
    result.add("deleted", entries("id", "secret/c"));
    result.add("images", entries("path", "shop/x.png", "secret/y.png"));
    result.add("removedImages", entries("path", "secret/z.png"));
    JsonArray conflicts = entries("id", "secret/d");
    conflicts.get(0).getAsJsonObject().addProperty("name", "Vault");
    result.add("conflicts", conflicts);
    JsonObject change = new JsonObject();
    change.addProperty("type", "change");
    change.add("result", result);

    events.publish(change);
    events.tick();
    events.tick();

    JsonObject seen = shop.sent.get(1).getAsJsonObject("result");
    assertEquals(1, seen.getAsJsonArray("documents").size());
    assertEquals(1, seen.getAsJsonArray("images").size());
    assertTrue(seen.getAsJsonArray("deleted").isEmpty());
    assertTrue(seen.getAsJsonArray("removedImages").isEmpty());
    assertTrue(seen.getAsJsonArray("conflicts").isEmpty());
    assertFalse(seen.toString().contains("secret"), seen.toString());
    assertFalse(seen.toString().contains("Vault"), seen.toString());
    assertEquals(2, all.sent.get(1).getAsJsonObject("result").getAsJsonArray("documents").size());
    assertEquals(result.get("message"), all.sent.get(1).getAsJsonObject("result").get("message"));
  }

  private static JsonArray entries(String field, String... values) {
    JsonArray entries = new JsonArray();
    for (String value : values) {
      JsonObject entry = new JsonObject();
      entry.addProperty("kind", field.equals("id") ? "menu" : "image");
      entry.addProperty(field, value);
      entries.add(entry);
    }
    return entries;
  }

  @Test
  void aBigBurstIsSentInFramesOfBoundedSize() {
    EditorEvents events = new EditorEvents();
    Sink sink = new Sink();
    events.add("tok_a", sink, (String folder) -> true, open());
    JsonObject burst = new JsonObject();
    burst.addProperty("type", "catalog");
    JsonArray documents = new JsonArray();
    for (int index = 0; index < 1575; index++) {
      JsonObject entry = new JsonObject();
      entry.addProperty("kind", "hologram");
      entry.addProperty("id", "seed-" + index);
      entry.addProperty("hash", "sha256:" + "a".repeat(64));
      entry.addProperty("path", "holograms/seed-" + index + ".json");
      documents.add(entry);
    }
    burst.add("documents", documents);
    events.publish(burst);
    for (int tick = 0; tick < 20; tick++) {
      events.tick();
    }

    int entries = 0;
    for (JsonObject frame : sink.sent.subList(1, sink.sent.size())) {
      assertTrue(frame.toString().length() < EditorEvents.CATALOG_FRAME_BYTES + 512);
      entries += frame.getAsJsonArray("documents").size();
    }
    assertEquals(1575, entries);
    assertTrue(sink.sent.size() <= 12);
  }
}
