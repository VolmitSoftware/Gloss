package art.arcane.gloss.editor;

import art.arcane.gloss.doc.AtomicFiles;
import art.arcane.gloss.editor.sync.EditorWorkspaceFixtures;
import art.arcane.volmlib.util.editor.PairingCode;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorApiTest {
  @TempDir
  Path data;

  private EditorTestServer server;

  @BeforeEach
  void start() throws Exception {
    EditorWorkspaceFixtures.hologram(data, "welcome", "Welcome");
    EditorWorkspaceFixtures.hologram(data, "spawn", "Spawn");
    EditorWorkspaceFixtures.menu(data, "shop/main", "{\"title\":\"&6Shop\",\"components\":[]}");
    EditorWorkspaceFixtures.menu(data, "lobby/main", "{\"components\":[]}");
    Files.createDirectories(data.resolve("images/shop"));
    Files.write(data.resolve("images/shop/coin.png"), EditorWorkspaceFixtures.png(1));
    server = new EditorTestServer(data);
  }

  @AfterEach
  void stop() {
    server.close();
  }

  @Test
  void helloNamesTheServerAndItsFingerprint() throws Exception {
    EditorTestServer.Answer hello = server.get("/v1/hello", null);

    assertEquals(200, hello.status());
    assertEquals(1, hello.body().get("protocol").getAsInt());
    assertEquals("Twin Peaks", hello.body().get("serverName").getAsString());
    assertEquals(server.service.api().orElseThrow().parts().identity().fingerprint(),
        hello.body().get("fingerprint").getAsString());
    assertEquals("paper", hello.body().get("platform").getAsString());
  }

  @Test
  void aLinkPairsOnceAndCarriesWhatTheCardShows() throws Exception {
    PairingCode code = server.link(EditorTestServer.grant(EditorScope.DEFAULT_PAIRING, List.of("shop")));
    assertEquals("Twin Peaks", code.serverName().orElseThrow());
    assertEquals(List.of("shop"), code.folders());
    assertTrue(code.directUrl().orElseThrow().startsWith("http://127.0.0.1:"));
    assertTrue(code.scopes().contains("read"));
    assertFalse(code.scopes().contains("publish:commands"));

    JsonObject body = new JsonObject();
    body.addProperty("tokenId", code.tokenId());
    body.addProperty("secret", code.secret());
    EditorTestServer.Answer first = server.post("/v1/pair", null, body, false);
    EditorTestServer.Answer second = server.post("/v1/pair", null, body, false);

    assertEquals(200, first.status());
    assertTrue(first.body().get("token").getAsString().startsWith("gls_" + code.tokenId() + "."));
    assertEquals(604800L, first.body().get("lifetimeSeconds").getAsLong());
    assertEquals(List.of("shop"), strings(first.body().getAsJsonArray("folders")));
    assertEquals(401, second.status());
    assertEquals("pairing-used", second.code());
  }

  @Test
  void aLinkExpiresAfterTenMinutesAndARevokedLinkIsCancelled() throws Exception {
    PairingCode late = server.link(EditorTestServer.grant(EditorScope.DEFAULT_PAIRING, List.of()));
    PairingCode cancelled = server.link(EditorTestServer.grant(EditorScope.DEFAULT_PAIRING, List.of()));
    server.service.revoke(cancelled.tokenId());
    server.clock.advance(Duration.ofMinutes(11));

    EditorTestServer.Answer expired = server.post("/v1/pair", null, exchange(late), false);
    EditorTestServer.Answer revoked = server.post("/v1/pair", null, exchange(cancelled), false);

    assertEquals(410, expired.status());
    assertEquals("pairing-expired", expired.code());
    assertEquals(410, revoked.status());
    assertEquals("pairing-expired", revoked.code());
    assertTrue(server.host.warnings.stream().anyMatch((String line) -> line.contains("the link expired before it was opened")));
  }

  @Test
  void aUsedLinkIsToldApartFromAWrongSecret() throws Exception {
    PairingCode code = server.link(EditorTestServer.grant(EditorScope.DEFAULT_PAIRING, List.of()));
    JsonObject wrongSecret = exchange(code);
    wrongSecret.addProperty("secret", "B".repeat(43));

    EditorTestServer.Answer first = server.post("/v1/pair", null, exchange(code), false);
    EditorTestServer.Answer again = server.post("/v1/pair", null, exchange(code), false);
    EditorTestServer.Answer wrong = server.post("/v1/pair", null, wrongSecret, false);

    assertEquals(200, first.status());
    assertEquals(401, wrong.status());
    assertEquals("pairing-invalid", wrong.code());
    assertEquals(401, again.status());
    assertEquals("pairing-used", again.code());
  }

  @Test
  void aRevokedLinkIsToldApartFromAWrongSecret() throws Exception {
    PairingCode code = server.link(EditorTestServer.grant(EditorScope.DEFAULT_PAIRING, List.of()));
    JsonObject wrongSecret = exchange(code);
    wrongSecret.addProperty("secret", "B".repeat(43));
    server.service.revoke(code.tokenId());

    EditorTestServer.Answer revoked = server.post("/v1/pair", null, exchange(code), false);
    EditorTestServer.Answer wrong = server.post("/v1/pair", null, wrongSecret, false);

    assertEquals(401, wrong.status());
    assertEquals("pairing-invalid", wrong.code());
    assertEquals(401, revoked.status());
    assertEquals("pairing-revoked", revoked.code());
  }

  @Test
  void anOriginOutsideAllowedOriginsIsRefusedAndLogged() throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(server.base() + "/v1/hello"))
        .header("Origin", "http://localhost:9999").GET().build();
    EditorTestServer.Answer refused = server.send(request);
    HttpRequest preflight = HttpRequest.newBuilder(URI.create(server.base() + "/v1/catalog"))
        .header("Origin", EditorTestServer.ORIGIN).method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
    EditorTestServer.Answer allowed = server.send(preflight);

    assertEquals(403, refused.status());
    assertEquals("origin-not-allowed", refused.code());
    assertTrue(server.host.warnings.contains("Editor refused origin http://localhost:9999: it is not in [editor] allowedOrigins"));
    assertEquals(204, allowed.status());
    assertEquals(EditorTestServer.ORIGIN, allowed.header("Access-Control-Allow-Origin").orElseThrow());
    assertTrue(allowed.header("Access-Control-Allow-Headers").orElseThrow().contains("X-Gloss-Counter"));
  }

  @Test
  void aRefusedOriginCanReadItsRefusalButNoData() throws Exception {
    String token = server.pair();
    String outsider = "http://localhost:9999";
    HttpRequest preflight = HttpRequest.newBuilder(URI.create(server.base() + "/v1/catalog"))
        .header("Origin", outsider).method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
    HttpRequest catalog = HttpRequest.newBuilder(URI.create(server.base() + "/v1/catalog"))
        .header("Origin", outsider).header("Authorization", "Bearer " + token).GET().build();

    EditorTestServer.Answer checked = server.send(preflight);
    EditorTestServer.Answer refused = server.send(catalog);

    assertEquals(204, checked.status());
    assertEquals(outsider, checked.header("Access-Control-Allow-Origin").orElseThrow());
    assertTrue(checked.header("Access-Control-Allow-Headers").orElseThrow().contains("Authorization"));
    assertEquals(403, refused.status());
    assertEquals("origin-not-allowed", refused.code());
    assertEquals(outsider, refused.header("Access-Control-Allow-Origin").orElseThrow());
    assertEquals("Origin", refused.header("Vary").orElseThrow());
    assertFalse(refused.body().has("documents"));
  }

  @Test
  void theCatalogListsEveryDocumentAndImageWithSummaries() throws Exception {
    String token = server.pair();

    EditorTestServer.Answer catalog = server.get("/v1/catalog", token);

    assertEquals(200, catalog.status());
    JsonArray documents = catalog.body().getAsJsonArray("documents");
    assertEquals(4, documents.size());
    JsonObject shop = find(documents, "menu", "shop/main");
    assertEquals("Shop", shop.getAsJsonObject("summary").get("name").getAsString());
    assertEquals("shop", shop.getAsJsonObject("summary").get("folder").getAsString());
    assertEquals("menus/shop/main.json", shop.get("path").getAsString());
    assertTrue(shop.get("hash").getAsString().startsWith("sha256:"));
    assertEquals(1L, find(documents, "hologram", "welcome").get("revision").getAsLong());
    assertEquals("shop/coin.png", catalog.body().getAsJsonArray("images").get(0).getAsJsonObject().get("path").getAsString());
    assertTrue(catalog.body().get("next").isJsonNull());
    assertTrue(catalog.body().get("metaHash").getAsString().startsWith("sha256:"));
  }

  @Test
  void theCatalogPagesAtAThousandEntries() throws Exception {
    for (int index = 0; index < 1100; index++) {
      EditorWorkspaceFixtures.hologram(data, String.format("bulk-%04d", index), "Bulk");
    }
    server.close();
    server = new EditorTestServer(data);
    String token = server.pair();

    EditorTestServer.Answer first = server.get("/v1/catalog", token);
    String next = first.body().get("next").getAsString();
    EditorTestServer.Answer second = server.get("/v1/catalog?after=" + next, token);

    assertEquals(1000, first.body().getAsJsonArray("documents").size());
    assertEquals(1104 - 1000, second.body().getAsJsonArray("documents").size());
    assertEquals(1, second.body().getAsJsonArray("images").size());
    assertTrue(second.body().get("next").isJsonNull());
  }

  @Test
  void batchReadsAnswerEveryRefWithABodyOrAnError() throws Exception {
    String token = server.pair(EditorTestServer.grant(EditorScope.DEFAULT_PAIRING, List.of("shop")));
    JsonObject request = new JsonObject();
    JsonArray refs = new JsonArray();
    refs.add(ref("menu", "shop/main"));
    refs.add(ref("menu", "lobby/main"));
    refs.add(ref("menu", "shop/gone"));
    request.add("refs", refs);

    EditorTestServer.Answer read = server.post("/v1/documents/read", token, request, false);

    assertEquals(200, read.status());
    assertEquals(1, read.body().getAsJsonArray("documents").size());
    assertEquals("{\"title\":\"&6Shop\",\"components\":[]}",
        read.body().getAsJsonArray("documents").get(0).getAsJsonObject().get("json").getAsString());
    List<String> codes = new ArrayList<>();
    for (JsonElement error : read.body().getAsJsonArray("errors")) {
      codes.add(error.getAsJsonObject().get("code").getAsString());
    }
    assertEquals(List.of("folder-denied", "not-found"), codes);
    assertTrue(read.body().getAsJsonArray("remaining").isEmpty());
  }

  @Test
  void aFolderLimitedTokenSeesOnlyItsFolders() throws Exception {
    String token = server.pair(EditorTestServer.grant(EditorScope.DEFAULT_PAIRING, List.of("shop")));

    EditorTestServer.Answer catalog = server.get("/v1/catalog", token);
    EditorTestServer.Answer lobby = server.get("/v1/documents/menu/lobby/main", token);

    assertEquals(1, catalog.body().getAsJsonArray("documents").size());
    assertEquals(1, catalog.body().getAsJsonArray("images").size());
    assertEquals(403, lobby.status());
    assertEquals("folder-denied", lobby.code());
  }

  @Test
  void bodiesComeInPagesAndSingleReads() throws Exception {
    String token = server.pair();

    EditorTestServer.Answer page = server.get("/v1/documents?maxBytes=1024", token);
    EditorTestServer.Answer single = server.get("/v1/documents/hologram/welcome", token);

    assertTrue(page.body().getAsJsonArray("documents").size() >= 1);
    assertFalse(page.body().get("next").isJsonNull());
    assertEquals("welcome", single.body().getAsJsonObject("summary").get("name").getAsString());
  }

  @Test
  void imagesAreReadByHashAndUploadsAreStagedForAPublish() throws Exception {
    String token = server.pair();
    String coin = server.get("/v1/catalog", token).body().getAsJsonArray("images").get(0).getAsJsonObject()
        .get("hash").getAsString();
    JsonObject read = new JsonObject();
    JsonArray hashes = new JsonArray();
    hashes.add(coin);
    hashes.add("sha256:" + "0".repeat(64));
    read.add("hashes", hashes);

    EditorTestServer.Answer images = server.post("/v1/images/read", token, read, false);
    JsonObject upload = new JsonObject();
    upload.addProperty("mediaType", "image/png");
    upload.addProperty("data", Base64.getEncoder().encodeToString(EditorWorkspaceFixtures.png(7)));
    EditorTestServer.Answer staged = server.post("/v1/images", token, upload, false);
    JsonObject publish = publish("img-0000000000000001");
    JsonArray imageChanges = new JsonArray();
    JsonObject put = new JsonObject();
    put.addProperty("op", "put");
    put.addProperty("path", "shop/new.png");
    put.add("baseHash", null);
    put.addProperty("hash", staged.body().get("hash").getAsString());
    imageChanges.add(put);
    publish.add("images", imageChanges);
    EditorTestServer.Answer published = server.post("/v1/changes", token, publish, true);

    assertEquals(1, images.body().getAsJsonArray("images").size());
    assertArrayEqualsBytes(EditorWorkspaceFixtures.png(1),
        Base64.getDecoder().decode(images.body().getAsJsonArray("images").get(0).getAsJsonObject().get("data").getAsString()));
    assertEquals(1, images.body().getAsJsonArray("missing").size());
    assertEquals(201, staged.status());
    assertEquals("applied", published.body().get("status").getAsString());
    assertTrue(Files.exists(data.resolve("images/shop/new.png")));
    assertEquals("shop/new.png", published.body().getAsJsonArray("images").get(0).getAsJsonObject().get("path").getAsString());
  }

  @Test
  void metaAnswersNotModifiedForItsHash() throws Exception {
    String token = server.pair();

    EditorTestServer.Answer meta = server.get("/v1/meta", token);
    String hash = meta.body().get("metaHash").getAsString();
    EditorTestServer.Answer again = server.get("/v1/meta", token, Map.of("If-None-Match", "\"" + hash + "\""));

    assertEquals(200, meta.status());
    JsonObject hologram = null;
    for (JsonElement kind : meta.body().getAsJsonObject("meta").getAsJsonArray("kinds")) {
      if (kind.getAsJsonObject().get("kind").getAsString().equals("hologram")) {
        hologram = kind.getAsJsonObject();
      }
    }
    assertNotNull(hologram);
    assertEquals("publish:world", hologram.get("scope").getAsString());
    assertEquals(304, again.status());
  }

  @Test
  void notesCarryWarningsAndHistoryAndHistoryReadsAStoredVersion() throws Exception {
    Files.writeString(data.resolve("holograms/broken.json"), "not json");
    server.close();
    server = new EditorTestServer(data);
    String token = server.pair();

    EditorTestServer.Answer notes = server.get("/v1/notes", token);
    EditorTestServer.Answer stored = server.get("/v1/history?kind=holograms&id=welcome&version=1700", token);
    EditorTestServer.Answer gone = server.get("/v1/history?kind=holograms&id=welcome&version=1", token);

    assertTrue(notes.body().getAsJsonObject("notes").getAsJsonArray("warnings").toString().contains("unreadable|hologram|broken||"));
    assertEquals(2, notes.body().getAsJsonObject("notes").getAsJsonArray("history").size());
    assertTrue(stored.body().get("found").getAsBoolean());
    assertFalse(gone.body().get("found").getAsBoolean());
  }

  @Test
  void aPublishAppliesWhatDidNotMoveAndNamesTheConflicts() throws Exception {
    String token = server.pair();
    JsonArray documents = server.get("/v1/catalog", token).body().getAsJsonArray("documents");
    String welcome = find(documents, "hologram", "welcome").get("hash").getAsString();
    String spawn = find(documents, "hologram", "spawn").get("hash").getAsString();
    EditorWorkspaceFixtures.hologram(data, "spawn", "Moved on the server");
    JsonObject publish = publish("pub-0000000000000001");
    JsonArray changes = new JsonArray();
    changes.add(put("hologram", "welcome", welcome, EditorWorkspaceFixtures.hologramJson(1L, "Edited")));
    changes.add(put("hologram", "spawn", spawn, EditorWorkspaceFixtures.hologramJson(1L, "Mine")));
    publish.add("documents", changes);

    EditorTestServer.Answer answer = server.post("/v1/changes", token, publish, true);
    EditorTestServer.Answer replay = server.post("/v1/changes", token, publish, true);

    assertEquals("applied", answer.body().get("status").getAsString());
    assertEquals("Published 1 change; 1 kept the server copy: spawn.", answer.body().get("message").getAsString());
    assertEquals("changed", answer.body().getAsJsonArray("conflicts").get(0).getAsJsonObject().get("reason").getAsString());
    assertEquals(2L, find(answer.body().getAsJsonArray("documents"), "hologram", "welcome").get("revision").getAsLong());
    assertTrue(Files.readString(data.resolve("holograms/welcome.json")).contains("Edited"));
    assertTrue(Files.readString(data.resolve("holograms/spawn.json")).contains("Moved on the server"));
    assertEquals(answer.body().get("changeId"), replay.body().get("changeId"));
  }

  @Test
  void aPublishThatDeletesMostOfTheServerNeedsTheCountConfirmed() throws Exception {
    restartWith(40);
    String token = server.pair();
    JsonObject publish = deletes(token, "pub-0000000000000010", 30);

    EditorTestServer.Answer refused = server.post("/v1/changes", token, publish, true);
    publish.addProperty("confirmDeletes", 29);
    EditorTestServer.Answer wrongCount = server.post("/v1/changes", token, publish, true);
    boolean keptWhileRefused = Files.exists(data.resolve("holograms/bulk-00.json"));
    publish.addProperty("confirmDeletes", 30);
    EditorTestServer.Answer confirmed = server.post("/v1/changes", token, publish, true);

    assertEquals(428, refused.status());
    assertEquals("confirm-deletes", refused.code());
    assertEquals(30, refused.body().getAsJsonObject("error").get("deletes").getAsInt());
    assertEquals(45, refused.body().getAsJsonObject("error").get("total").getAsInt());
    assertEquals("Publishing would delete 30 of the 45 documents and images on this server. Nothing was published.",
        refused.body().getAsJsonObject("error").get("message").getAsString());
    assertEquals("confirm-deletes", wrongCount.code());
    assertTrue(keptWhileRefused);
    assertEquals("applied", confirmed.body().get("status").getAsString());
    assertEquals(30, confirmed.body().getAsJsonArray("deleted").size());
    assertFalse(Files.exists(data.resolve("holograms/bulk-00.json")));
  }

  @Test
  void aPublishThatDeletesTwentyFiveOrAFifthOrLessNeedsNoConfirmation() throws Exception {
    restartWith(200);
    String token = server.pair();

    EditorTestServer.Answer few = server.post("/v1/changes", token, deletes(token, "pub-0000000000000011", 25), true);
    server.clock.advance(EditorPublishRoutes.CONFIRM_DELETES_WINDOW.plusMinutes(1));
    EditorTestServer.Answer share = server.post("/v1/changes", token, deletes(token, "pub-0000000000000012", 30), true);

    assertEquals("applied", few.body().get("status").getAsString(), few.body().toString());
    assertEquals("applied", share.body().get("status").getAsString(), share.body().toString());
    assertEquals(55, few.body().getAsJsonArray("deleted").size() + share.body().getAsJsonArray("deleted").size());
  }

  /** Restarts the server over the start documents plus {@code count} holograms named bulk-NN. */
  private void restartWith(int count) throws Exception {
    server.close();
    for (int index = 0; index < count; index++) {
      EditorWorkspaceFixtures.hologram(data, String.format("bulk-%02d", index), "Bulk " + index);
    }
    server = new EditorTestServer(data);
  }

  /** A publish deleting the first {@code count} bulk holograms still on the server. */
  private JsonObject deletes(String token, String key, int count) throws Exception {
    JsonArray changes = new JsonArray();
    for (JsonElement element : server.get("/v1/catalog", token).body().getAsJsonArray("documents")) {
      JsonObject row = element.getAsJsonObject();
      if (changes.size() < count && row.get("id").getAsString().startsWith("bulk-")) {
        JsonObject change = new JsonObject();
        change.addProperty("op", "delete");
        change.addProperty("kind", "hologram");
        change.addProperty("id", row.get("id").getAsString());
        change.addProperty("baseHash", row.get("hash").getAsString());
        changes.add(change);
      }
    }
    JsonObject publish = publish(key);
    publish.add("documents", changes);
    return publish;
  }

  @Test
  void aRetriedPublishOfWhatTheServerAlreadyHoldsIsNotAConflict() throws Exception {
    String token = server.pair();
    String welcome = find(server.get("/v1/catalog", token).body().getAsJsonArray("documents"), "hologram", "welcome")
        .get("hash").getAsString();
    JsonObject first = publish("pub-0000000000000002");
    JsonArray changes = new JsonArray();
    changes.add(put("hologram", "welcome", welcome, EditorWorkspaceFixtures.hologramJson(1L, "Edited")));
    first.add("documents", changes);
    server.post("/v1/changes", token, first, true);
    JsonObject retry = publish("pub-0000000000000003");
    retry.add("documents", changes);

    EditorTestServer.Answer answer = server.post("/v1/changes", token, retry, true);

    assertEquals("applied", answer.body().get("status").getAsString());
    assertTrue(answer.body().getAsJsonArray("conflicts").isEmpty());
    assertEquals("Nothing to change.", answer.body().get("message").getAsString());
  }

  @Test
  void aPublishNeedsItsCounterItsScopesAndPublishCommandsForConsoleCommands() throws Exception {
    String token = server.pair(EditorTestServer.grant(List.of(EditorScope.READ, EditorScope.PUBLISH_SCREENS), List.of()));
    String shop = find(server.get("/v1/catalog", token).body().getAsJsonArray("documents"), "menu", "shop/main")
        .get("hash").getAsString();
    JsonObject commands = publish("pub-0000000000000004");
    JsonArray menu = new JsonArray();
    menu.add(put("menu", "shop/main", shop, "{\"components\":[{\"id\":\"buy\",\"offset\":[0,0,0],\"data\":{\"type\":\"button\","
        + "\"actions\":[{\"type\":\"command\",\"source\":\"server\",\"command\":\"give @p diamond\"}]}}]}"));
    commands.add("documents", menu);
    JsonObject world = publish("pub-0000000000000005");
    JsonArray hologram = new JsonArray();
    hologram.add(put("hologram", "welcome", "sha256:" + "1".repeat(64), EditorWorkspaceFixtures.hologramJson(1L, "x")));
    world.add("documents", hologram);

    EditorTestServer.Answer uncounted = server.post("/v1/changes", token, commands, false);
    EditorTestServer.Answer refused = server.post("/v1/changes", token, commands, true);
    EditorTestServer.Answer missing = server.post("/v1/changes", token, world, true);

    assertEquals(400, uncounted.status());
    assertEquals(403, refused.status());
    assertEquals("scope-missing", refused.code());
    assertEquals("publish:commands", refused.body().getAsJsonObject("error").get("scope").getAsString());
    assertEquals(403, missing.status());
    assertEquals("publish:world", missing.body().getAsJsonObject("error").get("scope").getAsString());
  }

  @Test
  void aReplayedCounterIsRefusedWithTheCurrentValue() throws Exception {
    String token = server.pair();
    JsonObject body = publish("pub-0000000000000006");
    JsonArray none = new JsonArray();
    none.add(put("hologram", "welcome", "sha256:" + "2".repeat(64), EditorWorkspaceFixtures.hologramJson(1L, "y")));
    body.add("documents", none);
    HttpRequest replay = HttpRequest.newBuilder(URI.create(server.base() + "/v1/changes"))
        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).header("Origin", EditorTestServer.ORIGIN)
        .header("Authorization", "Bearer " + token).header("X-Gloss-Counter", "5").build();

    EditorTestServer.Answer first = server.send(replay);
    EditorTestServer.Answer second = server.send(replay);

    assertEquals(200, first.status());
    assertEquals(409, second.status());
    assertEquals("counter-replayed", second.code());
    assertEquals(5L, second.body().getAsJsonObject("error").get("counter").getAsLong());
  }

  @Test
  void aTokenLastsSevenDaysAfterItsLastUse() throws Exception {
    String token = server.pair();
    String first = server.get("/v1/token", token).body().get("expiresAt").getAsString();
    server.clock.advance(Duration.ofDays(6));
    String slid = server.get("/v1/token", token).body().get("expiresAt").getAsString();
    server.clock.advance(Duration.ofDays(8));

    EditorTestServer.Answer expired = server.get("/v1/token", token);

    assertTrue(slid.compareTo(first) > 0);
    assertEquals(401, expired.status());
    assertEquals("token-expired", expired.code());
  }

  @Test
  void tokensAndTheIdentitySurviveARestart() throws Exception {
    String token = server.pair();
    String fingerprint = server.service.api().orElseThrow().parts().identity().fingerprint();
    server.close();
    server = new EditorTestServer(data);

    assertEquals(200, server.get("/v1/token", token).status());
    assertEquals(fingerprint, server.service.api().orElseThrow().parts().identity().fingerprint());
  }

  @Test
  void pairingPastTheLimitSaysHowLongToWait() throws Exception {
    JsonObject attempt = new JsonObject();
    attempt.addProperty("tokenId", "tok_AAAAAAAAAAAA");
    attempt.addProperty("secret", "A".repeat(43));
    server.clock.advance(Duration.ofSeconds(5));
    for (int index = 0; index < 10; index++) {
      server.post("/v1/pair", null, attempt, false);
    }

    EditorTestServer.Answer limited = server.post("/v1/pair", null, attempt, false);
    server.clock.advance(Duration.ofSeconds(40));
    EditorTestServer.Answer later = server.post("/v1/pair", null, attempt, false);
    server.clock.advance(Duration.ofSeconds(15));
    EditorTestServer.Answer nextMinute = server.post("/v1/pair", null, attempt, false);

    assertEquals("rate-limited", limited.code());
    assertEquals(55_000L, limited.body().getAsJsonObject("error").get("retryAfterMillis").getAsLong());
    assertEquals("Too many pairing attempts from this address. Try again in 55 s.",
        limited.body().getAsJsonObject("error").get("message").getAsString());
    assertEquals("Too many pairing attempts from this address. Try again in 15 s.",
        later.body().getAsJsonObject("error").get("message").getAsString());
    assertEquals("pairing-invalid", nextMinute.code());
  }

  @Test
  void requestsPastTheRateAreRefusedWithARetryHint() throws Exception {
    String token = server.pair();
    EditorTestServer.Answer limited = null;
    for (int index = 0; index < 80 && limited == null; index++) {
      EditorTestServer.Answer answer = server.get("/v1/token", token);
      if (answer.status() == 429) {
        limited = answer;
      }
    }

    assertNotNull(limited);
    assertEquals("rate-limited", limited.code());
    assertTrue(limited.body().getAsJsonObject("error").get("retryAfterMillis").getAsLong() > 0);
    assertTrue(limited.header("Retry-After").isPresent());
  }

  @Test
  void eventStreamsOpenWithTheTokenExpiryAndCarryChangesFromEverySource() throws Exception {
    String token = server.pair();
    EditorTestServer.Events events = server.events(token);
    JsonObject open = events.next("open", Duration.ofSeconds(5));
    assertNotNull(open);
    assertTrue(open.has("expiresAt"));

    long written = System.nanoTime();
    AtomicFiles.replace(data.resolve("holograms/welcome.json"),
        EditorWorkspaceFixtures.hologramJson(2L, "From the console").getBytes(StandardCharsets.UTF_8));
    JsonObject serverChange = events.next("catalog", Duration.ofSeconds(3));
    long serverMillis = (System.nanoTime() - written) / 1_000_000L;
    Files.writeString(data.resolve("holograms/spawn.json"), EditorWorkspaceFixtures.hologramJson(2L, "By hand"));
    Files.setLastModifiedTime(data.resolve("holograms/spawn.json"),
        java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000L));
    JsonObject fileChange = events.next("catalog", Duration.ofSeconds(4));
    server.service.revoke("all");
    JsonObject closing = events.next("closing", Duration.ofSeconds(3));
    events.close();

    assertNotNull(serverChange);
    assertEquals("server", serverChange.getAsJsonArray("documents").get(0).getAsJsonObject().get("source").getAsString());
    assertEquals("welcome", serverChange.getAsJsonArray("documents").get(0).getAsJsonObject().get("id").getAsString());
    assertTrue(serverMillis < 1000L, "console change took " + serverMillis + " ms");
    assertNotNull(fileChange);
    assertEquals("spawn", fileChange.getAsJsonArray("documents").get(0).getAsJsonObject().get("id").getAsString());
    assertEquals("file", fileChange.getAsJsonArray("documents").get(0).getAsJsonObject().get("source").getAsString());
    assertNotNull(closing);
    assertEquals("token-revoked", closing.get("reason").getAsString());
  }

  @Test
  void aPublishSendsTheChangeThenTheCatalogToOpenStreams() throws Exception {
    String token = server.pair();
    EditorTestServer.Events events = server.events(token);
    assertNotNull(events.next("open", Duration.ofSeconds(5)));
    String welcome = find(server.get("/v1/catalog", token).body().getAsJsonArray("documents"), "hologram", "welcome")
        .get("hash").getAsString();
    JsonObject publish = publish("pub-0000000000000007");
    JsonArray changes = new JsonArray();
    changes.add(put("hologram", "welcome", welcome, EditorWorkspaceFixtures.hologramJson(1L, "Published")));
    publish.add("documents", changes);

    server.post("/v1/changes", token, publish, true);
    JsonObject change = events.next("change", Duration.ofSeconds(3));
    JsonObject catalog = events.next("catalog", Duration.ofSeconds(3));
    events.close();

    assertNotNull(change);
    assertEquals("applied", change.getAsJsonObject("result").get("status").getAsString());
    assertNotNull(catalog);
    assertEquals("editor", catalog.getAsJsonArray("documents").get(0).getAsJsonObject().get("source").getAsString());
  }

  @Test
  void aStreamWithAnUnknownTokenIsClosedWithItsCode() throws Exception {
    EditorTestServer.Events events = server.events("gls_tok_AAAAAAAAAAAAAAAA." + "A".repeat(43));

    assertNull(events.next("open", Duration.ofSeconds(2)));
    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (events.closeCode < 0 && System.nanoTime() < deadline) {
      Thread.sleep(20L);
    }
    assertEquals(4401, events.closeCode);
    assertEquals("unauthorized", events.closeReason);
  }

  @Test
  void consoleCommandsNeedPublishCommandsHoweverTheSourceIsSpelled() throws Exception {
    String token = server.pair(EditorTestServer.grant(List.of(EditorScope.READ, EditorScope.PUBLISH_SCREENS), List.of()));
    List<String> sources = List.of("", "\"server\"", "\"GLOBAL\"", "\"global\"", "\"player\"");
    List<Integer> statuses = new ArrayList<>();
    for (int index = 0; index < sources.size(); index++) {
      JsonObject publish = publish("cmd-000000000000000" + index);
      JsonArray menu = new JsonArray();
      menu.add(put("menu", "probe/menu-" + index, null, commandMenu(sources.get(index))));
      publish.add("documents", menu);
      statuses.add(server.post("/v1/changes", token, publish, true).status());
    }

    assertEquals(List.of(200, 403, 403, 200, 200), statuses);
    assertTrue(Files.exists(data.resolve("menus/probe/menu-3.json")));
    assertFalse(Files.exists(data.resolve("menus/probe/menu-2.json")));
  }

  @Test
  void theCatalogMarksConsoleCommandsHoweverTheSourceIsSpelled() throws Exception {
    server.close();
    EditorWorkspaceFixtures.menu(data, "probe/upper", commandMenu("\"GLOBAL\""));
    EditorWorkspaceFixtures.menu(data, "probe/lower", commandMenu("\"global\""));
    server = new EditorTestServer(data);
    String token = server.pair();

    JsonArray documents = server.get("/v1/catalog", token).body().getAsJsonArray("documents");

    assertTrue(find(documents, "menu", "probe/upper").getAsJsonObject("summary").has("commands"));
    assertFalse(find(documents, "menu", "probe/lower").getAsJsonObject("summary").has("commands"));
  }

  @Test
  void aBehaviorThatRunsAnyCommandNeedsPublishCommands() throws Exception {
    String token = server.pair();
    JsonObject commands = publish("beh-0000000000000001");
    JsonArray command = new JsonArray();
    command.add(put("behavior", "probe", null,
        behavior("{\"type\":\"command\",\"source\":\"player\",\"command\":\"op mallory\"}")));
    commands.add("documents", command);
    JsonObject messages = publish("beh-0000000000000002");
    JsonArray message = new JsonArray();
    message.add(put("behavior", "greeting", null, behavior("{\"type\":\"message\",\"message\":\"Hi\"}")));
    messages.add("documents", message);

    EditorTestServer.Answer refused = server.post("/v1/changes", token, commands, true);
    EditorTestServer.Answer applied = server.post("/v1/changes", token, messages, true);
    JsonArray documents = server.get("/v1/catalog", token).body().getAsJsonArray("documents");

    assertEquals(403, refused.status());
    assertEquals("scope-missing", refused.code());
    assertEquals("applied", applied.body().get("status").getAsString(), applied.body().toString());
    assertFalse(find(documents, "behavior", "greeting").getAsJsonObject("summary").has("commands"));
  }

  @Test
  void aResentPublishGetsItsFirstAnswerAfterItsUploadIsGone() throws Exception {
    String token = server.pair();
    EditorTestServer.Events events = server.events(token);
    assertNotNull(events.next("open", Duration.ofSeconds(5)));
    JsonObject upload = new JsonObject();
    upload.addProperty("mediaType", "image/png");
    upload.addProperty("data", Base64.getEncoder().encodeToString(EditorWorkspaceFixtures.png(9)));
    String hash = server.post("/v1/images", token, upload, false).body().get("hash").getAsString();
    JsonObject publish = publish("img-0000000000000002");
    JsonArray images = new JsonArray();
    JsonObject put = new JsonObject();
    put.addProperty("op", "put");
    put.addProperty("path", "shop/late.png");
    put.add("baseHash", null);
    put.addProperty("hash", hash);
    images.add(put);
    publish.add("images", images);

    EditorTestServer.Answer first = server.post("/v1/changes", token, publish, true);
    Files.delete(data.resolve("images/shop/late.png"));
    JsonObject removal = nextWith(events, "removedImages", Duration.ofSeconds(5));
    events.close();
    server.clock.advance(EditorUploads.LIFETIME.plusMinutes(1));
    EditorTestServer.Answer resent = server.post("/v1/changes", token, publish, true);

    assertNotNull(removal);
    assertEquals("applied", first.body().get("status").getAsString());
    assertEquals(200, resent.status(), resent.body().toString());
    assertEquals(first.body().get("changeId"), resent.body().get("changeId"));
  }

  @Test
  void aPublishAnswersOnlyWhatItSentAndTellsOtherChangesAsTheServers() throws Exception {
    String token = server.pair();
    EditorTestServer.Events events = server.events(token);
    assertNotNull(events.next("open", Duration.ofSeconds(5)));
    String welcome = find(server.get("/v1/catalog", token).body().getAsJsonArray("documents"), "hologram", "welcome")
        .get("hash").getAsString();
    JsonObject publish = publish("pub-0000000000000020");
    JsonArray changes = new JsonArray();
    changes.add(put("hologram", "welcome", welcome, EditorWorkspaceFixtures.hologramJson(1L, "Published")));
    publish.add("documents", changes);

    Files.writeString(data.resolve("holograms/spawn.json"), EditorWorkspaceFixtures.hologramJson(2L, "In game"));
    EditorTestServer.Answer answer = server.post("/v1/changes", token, publish, true);
    List<JsonObject> catalogs = new ArrayList<>();
    for (JsonObject frame = events.next("catalog", Duration.ofSeconds(3)); frame != null;
         frame = events.next("catalog", Duration.ofSeconds(2))) {
      catalogs.add(frame);
    }
    events.close();

    assertEquals(List.of("welcome"), ids(answer.body().getAsJsonArray("documents")));
    List<String> editor = new ArrayList<>();
    List<String> others = new ArrayList<>();
    for (JsonObject catalog : catalogs) {
      if (!catalog.has("documents")) {
        continue;
      }
      for (JsonElement element : catalog.getAsJsonArray("documents")) {
        JsonObject entry = element.getAsJsonObject();
        (entry.get("source").getAsString().equals("editor") ? editor : others).add(entry.get("id").getAsString());
      }
    }
    assertEquals(List.of("welcome"), editor);
    assertEquals(List.of("spawn"), others);
  }

  @Test
  void deletesSpreadOverSeveralPublishesStillNeedTheCountConfirmed() throws Exception {
    restartWith(40);
    String token = server.pair();

    EditorTestServer.Answer first = server.post("/v1/changes", token, deletes(token, "pub-0000000000000030", 10), true);
    EditorTestServer.Answer second = server.post("/v1/changes", token, deletes(token, "pub-0000000000000031", 10), true);
    JsonObject third = deletes(token, "pub-0000000000000032", 10);
    EditorTestServer.Answer refused = server.post("/v1/changes", token, third, true);
    third.addProperty("confirmDeletes", refused.body().getAsJsonObject("error").get("deletes").getAsInt());
    EditorTestServer.Answer confirmed = server.post("/v1/changes", token, third, true);

    assertEquals("applied", first.body().get("status").getAsString());
    assertEquals("applied", second.body().get("status").getAsString());
    assertEquals(428, refused.status());
    assertEquals(30, refused.body().getAsJsonObject("error").get("deletes").getAsInt());
    assertEquals("applied", confirmed.body().get("status").getAsString(), confirmed.body().toString());
  }

  @Test
  void theDeleteShareCountsOnlyWhatTheTokenMayPublish() throws Exception {
    for (int index = 0; index < 15; index++) {
      EditorWorkspaceFixtures.menu(data, String.format("extra/menu-%02d", index), "{\"components\":[]}");
    }
    restartWith(118);
    String token = server.pair(EditorTestServer.grant(List.of(EditorScope.READ, EditorScope.PUBLISH_WORLD), List.of()));

    EditorTestServer.Answer refused = server.post("/v1/changes", token, deletes(token, "pub-0000000000000033", 26), true);

    assertEquals(428, refused.status(), refused.body().toString());
    assertEquals(120, refused.body().getAsJsonObject("error").get("total").getAsInt());
  }

  @Test
  void anImagePathMustBeWrittenTheWayTheServerStoresIt() throws Exception {
    String token = server.pair();
    byte[] before = Files.readAllBytes(data.resolve("images/shop/coin.png"));
    JsonObject upload = new JsonObject();
    upload.addProperty("mediaType", "image/png");
    upload.addProperty("data", Base64.getEncoder().encodeToString(EditorWorkspaceFixtures.png(5)));
    String hash = server.post("/v1/images", token, upload, false).body().get("hash").getAsString();
    List<Integer> statuses = new ArrayList<>();
    List<String> paths = List.of("shop\\coin.png", "shop/coin.png ", "./shop/coin.png");
    for (int index = 0; index < paths.size(); index++) {
      JsonObject publish = publish("img-000000000000001" + index);
      JsonArray images = new JsonArray();
      JsonObject put = new JsonObject();
      put.addProperty("op", "put");
      put.addProperty("path", paths.get(index));
      put.add("baseHash", null);
      put.addProperty("hash", hash);
      images.add(put);
      publish.add("images", images);
      statuses.add(server.post("/v1/changes", token, publish, true).status());
    }

    assertEquals(List.of(400, 400, 400), statuses);
    assertArrayEqualsBytes(before, Files.readAllBytes(data.resolve("images/shop/coin.png")));
  }

  /** The next catalog frame carrying {@code field}, skipping others; null when none came in time. */
  private static JsonObject nextWith(EditorTestServer.Events events, String field, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      JsonObject frame = events.next("catalog", Duration.ofNanos(Math.max(1L, deadline - System.nanoTime())));
      if (frame != null && frame.has(field)) {
        return frame;
      }
    }
    return null;
  }

  private static String commandMenu(String source) {
    return "{\"components\":[{\"id\":\"buy\",\"offset\":[0,0,0],\"data\":{\"type\":\"button\",\"actions\":[{"
        + "\"type\":\"command\"" + (source.isEmpty() ? "" : ",\"source\":" + source) + ",\"command\":\"say hi\"}]}}]}";
  }

  private static String behavior(String action) {
    return "{\"schemaVersion\":2,\"revision\":1,\"on\":[{\"trigger\":\"join\",\"do\":[" + action + "]}]}";
  }

  private static List<String> ids(JsonArray rows) {
    List<String> ids = new ArrayList<>();
    rows.forEach((JsonElement element) -> ids.add(element.getAsJsonObject().get("id").getAsString()));
    return ids;
  }

  private static JsonObject exchange(PairingCode code) {
    JsonObject body = new JsonObject();
    body.addProperty("tokenId", code.tokenId());
    body.addProperty("secret", code.secret());
    return body;
  }

  private static JsonObject publish(String key) {
    JsonObject body = new JsonObject();
    body.addProperty("idempotencyKey", key);
    return body;
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

  private static JsonObject ref(String kind, String id) {
    JsonObject ref = new JsonObject();
    ref.addProperty("kind", kind);
    ref.addProperty("id", id);
    return ref;
  }

  private static JsonObject find(JsonArray documents, String kind, String id) {
    for (JsonElement element : documents) {
      JsonObject document = element.getAsJsonObject();
      if (document.get("kind").getAsString().equals(kind) && document.get("id").getAsString().equals(id)) {
        return document;
      }
    }
    throw new AssertionError("no " + kind + " " + id + " in " + documents);
  }

  private static List<String> strings(JsonArray array) {
    List<String> values = new ArrayList<>();
    array.forEach((JsonElement element) -> values.add(element.getAsString()));
    return values;
  }

  private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
    org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
  }
}
