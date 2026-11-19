package art.arcane.gloss.editor;

import art.arcane.gloss.doc.AtomicFiles;
import art.arcane.gloss.editor.sync.EditorWorkspaceFixtures;
import art.arcane.volmlib.util.editor.EditorTokens;
import art.arcane.volmlib.util.editor.PairingCode;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The golden messages in {@code schema/editor/fixtures/api}, which the web editor's tests read, keep the shape of
 * real answers: every fixture's keys and value types match what this server sends. Run with
 * {@code GLOSS_WRITE_EDITOR_FIXTURES=true} to write them again from a fresh scenario.
 */
class EditorFixturesTest {
  private static final Path DIRECTORY = Path.of("schema/editor/fixtures/api");
  private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

  @TempDir
  Path data;

  @Test
  void everyApiFixtureHasTheShapeOfARealAnswer() throws Exception {
    Map<String, JsonElement> messages = scenario();
    boolean write = "true".equals(System.getenv("GLOSS_WRITE_EDITOR_FIXTURES"));
    if (write) {
      Files.createDirectories(DIRECTORY);
      JsonArray index = new JsonArray();
      for (Map.Entry<String, JsonElement> message : messages.entrySet()) {
        Files.writeString(DIRECTORY.resolve(message.getKey() + ".json"), PRETTY.toJson(message.getValue()) + "\n",
            StandardCharsets.UTF_8);
        JsonObject entry = new JsonObject();
        entry.addProperty("name", message.getKey());
        entry.addProperty("path", "api/" + message.getKey() + ".json");
        index.add(entry);
      }
      JsonObject root = new JsonObject();
      root.addProperty("description", "Golden editor API 1 messages written by the Gloss plugin's EditorFixturesTest "
          + "from real answers. The editor API section of gloss-web/sync-relay/README.md describes each one.");
      root.add("messages", index);
      Files.writeString(DIRECTORY.resolve("index.json"), PRETTY.toJson(root) + "\n", StandardCharsets.UTF_8);
    }
    for (Map.Entry<String, JsonElement> message : messages.entrySet()) {
      Path file = DIRECTORY.resolve(message.getKey() + ".json");
      assertTrue(Files.exists(file), "missing fixture " + file);
      JsonElement golden = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
      assertEquals(shape(golden), shape(message.getValue()), message.getKey());
    }
  }

  private Map<String, JsonElement> scenario() throws Exception {
    Map<String, JsonElement> messages = new LinkedHashMap<>();
    EditorWorkspaceFixtures.hologram(data, "welcome", "Welcome");
    EditorWorkspaceFixtures.hologram(data, "spawn", "Spawn");
    EditorWorkspaceFixtures.menu(data, "shop/main", "{\"title\":\"&6Shop\",\"components\":[]}");
    Files.createDirectories(data.resolve("images/shop"));
    Files.write(data.resolve("images/shop/coin.png"), EditorWorkspaceFixtures.png(1));
    try (EditorTestServer server = new EditorTestServer(data)) {
      messages.put("hello-response", server.get("/v1/hello", null).body());
      EditorTokens.Grant<EditorScope> grant = new EditorTokens.Grant<>("laptop", Optional.empty(), EditorScope.DEFAULT_PAIRING,
          List.of("shop"), Duration.ofDays(7));
      String link = server.service.pair(grant).link();
      String encoded = link.substring(link.indexOf("#/pair/") + 7);
      PairingCode code = PairingCode.decode(EditorLink.PRODUCT, encoded);
      JsonObject pairing = new JsonObject();
      pairing.addProperty("link", link);
      pairing.addProperty("code", encoded);
      pairing.add("payload", code.payload());
      messages.put("pairing-code", pairing);
      JsonObject request = new JsonObject();
      request.addProperty("tokenId", code.tokenId());
      request.addProperty("secret", code.secret());
      request.addProperty("client", "Chrome on macOS");
      messages.put("pair-request", request);
      JsonObject paired = server.post("/v1/pair", null, request, false).body();
      messages.put("pair-response", paired);
      String limited = paired.get("token").getAsString();
      String token = server.pair();
      messages.put("token-response", server.get("/v1/token", limited).body());
      JsonObject catalog = server.get("/v1/catalog", token).body();
      messages.put("catalog-response", catalog);
      messages.put("documents-page-response", server.get("/v1/documents", token).body());
      JsonObject read = new JsonObject();
      JsonArray refs = new JsonArray();
      refs.add(ref("hologram", "welcome"));
      refs.add(ref("menu", "shop/gone"));
      read.add("refs", refs);
      messages.put("documents-read-request", read);
      messages.put("documents-read-response", server.post("/v1/documents/read", token, read, false).body());
      messages.put("document-response", server.get("/v1/documents/menu/shop/main", token).body());
      String coin = catalog.getAsJsonArray("images").get(0).getAsJsonObject().get("hash").getAsString();
      JsonObject images = new JsonObject();
      JsonArray hashes = new JsonArray();
      hashes.add(coin);
      images.add("hashes", hashes);
      messages.put("images-read-request", images);
      messages.put("images-read-response", server.post("/v1/images/read", token, images, false).body());
      JsonObject upload = new JsonObject();
      upload.addProperty("mediaType", "image/png");
      upload.addProperty("data", Base64.getEncoder().encodeToString(EditorWorkspaceFixtures.png(2)));
      messages.put("image-upload-request", upload);
      JsonObject uploaded = server.post("/v1/images", token, upload, false).body();
      messages.put("image-upload-response", uploaded);
      messages.put("meta-response", server.get("/v1/meta", token).body());
      messages.put("notes-response", server.get("/v1/notes", token).body());
      messages.put("history-response", server.get("/v1/history?kind=holograms&id=welcome&version=1700", token).body());

      EditorTestServer.Events events = server.events(token);
      JsonObject open = events.next("open", Duration.ofSeconds(5));
      messages.put("event-open", open);
      AtomicFiles.replace(data.resolve("holograms/spawn.json"),
          EditorWorkspaceFixtures.hologramJson(2L, "Moved on the server").getBytes(StandardCharsets.UTF_8));
      JsonObject serverCatalog = events.next("catalog", Duration.ofSeconds(4));
      while (serverCatalog != null && !serverCatalog.has("documents")) {
        serverCatalog = events.next("catalog", Duration.ofSeconds(4));
      }
      assertNotNull(serverCatalog);
      messages.put("event-catalog", serverCatalog);

      String welcome = hash(catalog, "hologram", "welcome");
      String spawn = hash(catalog, "hologram", "spawn");
      JsonObject publish = new JsonObject();
      publish.addProperty("idempotencyKey", "pub_4kB2f0Qx9w1mZr8c");
      JsonArray documents = new JsonArray();
      documents.add(put("hologram", "welcome", welcome, EditorWorkspaceFixtures.hologramJson(1L, "Edited in the browser")));
      documents.add(put("hologram", "spawn", spawn, EditorWorkspaceFixtures.hologramJson(1L, "Mine")));
      publish.add("documents", documents);
      JsonArray imageChanges = new JsonArray();
      JsonObject image = new JsonObject();
      image.addProperty("op", "put");
      image.addProperty("path", "shop/new.png");
      image.add("baseHash", com.google.gson.JsonNull.INSTANCE);
      image.addProperty("hash", uploaded.get("hash").getAsString());
      image.addProperty("mediaType", "image/png");
      imageChanges.add(image);
      publish.add("images", imageChanges);
      messages.put("changes-request", publish);
      messages.put("changes-response", server.post("/v1/changes", token, publish, true).body());
      messages.put("event-change", events.next("change", Duration.ofSeconds(4)));

      JsonObject denied = new JsonObject();
      denied.addProperty("idempotencyKey", "pub_0000000000000002");
      JsonArray menu = new JsonArray();
      menu.add(put("menu", "shop/main", hash(catalog, "menu", "shop/main"), "{\"components\":[{\"id\":\"buy\","
          + "\"offset\":[0,0,0],\"data\":{\"type\":\"button\",\"actions\":[{\"type\":\"command\",\"source\":\"server\","
          + "\"command\":\"give @p diamond\"}]}}]}"));
      denied.add("documents", menu);
      messages.put("error-response", server.post("/v1/changes", token, denied, true).body());
      EditorTestServer.Answer rate = null;
      for (int attempt = 0; attempt < 80 && rate == null; attempt++) {
        EditorTestServer.Answer answer = server.get("/v1/token", token);
        if (answer.status() == 429) {
          rate = answer;
        }
      }
      assertNotNull(rate);
      messages.put("error-rate-limited", rate.body());
      server.service.revoke("all");
      messages.put("event-closing", events.next("closing", Duration.ofSeconds(4)));
      events.close();
    }
    return messages;
  }

  /** Keys and value types, recursively; an array's shape is its first element's. */
  private static JsonElement shape(JsonElement element) {
    if (element == null || element.isJsonNull()) {
      return new com.google.gson.JsonPrimitive("null");
    }
    if (element.isJsonPrimitive()) {
      return new com.google.gson.JsonPrimitive(element.getAsJsonPrimitive().isNumber() ? "number"
          : element.getAsJsonPrimitive().isBoolean() ? "boolean" : "string");
    }
    if (element.isJsonArray()) {
      JsonArray array = new JsonArray();
      if (!element.getAsJsonArray().isEmpty()) {
        array.add(shape(element.getAsJsonArray().get(0)));
      }
      return array;
    }
    JsonObject object = new JsonObject();
    Map<String, JsonElement> sorted = new TreeMap<>(element.getAsJsonObject().asMap());
    for (Map.Entry<String, JsonElement> field : sorted.entrySet()) {
      object.add(field.getKey(), field.getKey().equals("schemas") || field.getKey().equals("defaults")
          ? new com.google.gson.JsonPrimitive("object") : shape(field.getValue()));
    }
    return object;
  }

  private static String hash(JsonObject catalog, String kind, String id) {
    for (JsonElement element : catalog.getAsJsonArray("documents")) {
      JsonObject document = element.getAsJsonObject();
      if (document.get("kind").getAsString().equals(kind) && document.get("id").getAsString().equals(id)) {
        return document.get("hash").getAsString();
      }
    }
    throw new AssertionError(kind + " " + id);
  }

  private static JsonObject ref(String kind, String id) {
    JsonObject ref = new JsonObject();
    ref.addProperty("kind", kind);
    ref.addProperty("id", id);
    return ref;
  }

  private static JsonObject put(String kind, String id, String baseHash, String json) {
    JsonObject change = new JsonObject();
    change.addProperty("op", "put");
    change.addProperty("kind", kind);
    change.addProperty("id", id);
    change.addProperty("baseHash", baseHash);
    change.addProperty("json", json);
    return change;
  }
}
