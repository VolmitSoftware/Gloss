package art.arcane.gloss.editor;

import art.arcane.gloss.editor.sync.EditorWorkspaceFixtures;
import art.arcane.volmlib.util.editor.EditorTokens;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class EditorServiceTest {
  @TempDir
  Path data;

  @Test
  void theOldLinkFolderIsRemovedAtStartWithOneLine() throws Exception {
    Files.createDirectories(data.resolve("editor-sync"));
    Files.writeString(data.resolve("editor-sync/abc.json"), "{}");
    Files.writeString(data.resolve("editor-sync/def.json"), "{}");

    try (EditorTestServer server = new EditorTestServer(data)) {
      assertFalse(Files.exists(data.resolve("editor-sync")));
      assertTrue(server.host.logs.contains("Removed 2 old web editor links; pair the editor again with /gloss editor pair"));
    }
  }

  @Test
  void aDamagedTokenStoreDoesNotStopTheEditor() throws Exception {
    Files.createDirectories(data.resolve("editor"));
    Files.writeString(data.resolve(EditorLink.TOKENS_PATH), "{\"tokens\":[");

    try (EditorTestServer server = new EditorTestServer(data)) {
      String token = server.pair();

      assertTrue(server.service.status().getFirst().startsWith("Editor ready"));
      assertEquals(1L, server.host.warnings.stream().filter((String line) -> line.contains("tokens.damaged-")).count());
      assertEquals(200, server.get("/v1/token", token).status());
    }
  }

  @Test
  void aRevokeThatCannotBeSavedStillEndsTheStreamAndSaysSo() throws Exception {
    try (EditorTestServer server = new EditorTestServer(data)) {
      String token = server.pair();
      EditorTestServer.Events events = server.events(token);
      assertNotNull(events.next("open", Duration.ofSeconds(5)));
      Path folder = data.resolve("editor");
      Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
      IOException failure;
      try {
        assumeFalse(Files.isWritable(folder), "this user writes into read-only folders");
        failure = assertThrows(IOException.class, () -> server.service.revoke("all"));
      } finally {
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
      }
      JsonObject closing = events.next("closing", Duration.ofSeconds(3));
      events.close();

      assertNotNull(closing);
      assertTrue(failure.getMessage().contains(EditorLink.TOKENS_PATH), failure.getMessage());
      assertEquals("token-revoked", server.get("/v1/token", token).code());
    }
  }

  @Test
  void statusNamesTheListenerTheRelayAndTheFingerprint() throws Exception {
    try (EditorTestServer server = new EditorTestServer(data)) {
      server.link(EditorTestServer.grant(EditorScope.DEFAULT_PAIRING, List.of()));
      List<String> status = server.service.status();

      assertTrue(status.get(0).startsWith("Editor ready | listener 127.0.0.1:" + server.service.listenerPort() + " (http)"));
      assertTrue(status.get(0).contains("relay off"));
      assertTrue(status.get(0).contains("0 tokens, 1 pairing"));
      assertEquals("Fingerprint " + server.service.api().orElseThrow().parts().identity().fingerprint(), status.get(1));
    }
  }

  @Test
  void statusSaysWhenTheRelayCannotBeReachedAndThatTheListenerStillWorks() throws Exception {
    try (EditorTestServer server = new EditorTestServer(data, EditorTestServer.settings(true, true))) {
      server.link(EditorTestServer.grant(EditorScope.DEFAULT_PAIRING, List.of()));
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (!server.service.status().getFirst().contains("unreachable") && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      List<String> status = server.service.status();

      assertTrue(status.get(0).contains("relay ws://127.0.0.1:9 unreachable"), status.get(0));
      assertTrue(status.get(1).contains("relay unreachable"), status.get(1));
      assertTrue(status.get(1).endsWith("the direct listener still works"), status.get(1));
      assertTrue(server.service.headline().contains("relay unreachable"), server.service.headline());
    }
  }

  @Test
  void withoutAListenerAnUnreachableRelaySaysNothingReachesTheServer() throws Exception {
    try (EditorTestServer server = new EditorTestServer(data, EditorTestServer.settings(false, true))) {
      server.link(EditorTestServer.grant(EditorScope.DEFAULT_PAIRING, List.of()));
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (!server.service.status().getFirst().contains("unreachable") && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }

      assertTrue(server.service.status().get(1).endsWith("no browser reaches this server until it answers"),
          server.service.status().get(1));
    }
  }

  @Test
  void openEndpointsTakeSmallBodiesAndOtherVersionsAreNamed() throws Exception {
    try (EditorTestServer server = new EditorTestServer(data)) {
      JsonObject large = new JsonObject();
      large.addProperty("tokenId", "x".repeat(5000));
      EditorTestServer.Answer tooLarge = server.post("/v1/pair", null, large, false);
      EditorTestServer.Answer other = server.send(HttpRequest.newBuilder(URI.create(server.base() + "/v2/hello"))
          .GET().build());
      EditorTestServer.Answer unknown = server.get("/v1/nothing", server.pair());

      assertEquals(413, tooLarge.status());
      assertEquals("too-large", tooLarge.code());
      assertEquals("unsupported-protocol", other.code());
      assertEquals(404, unknown.status());
    }
  }

  @Test
  void aPlayerWhoLosesThePairNodeLosesTheTokenAndLosingCommandsDropsOnlyThatScope() throws Exception {
    try (EditorTestServer server = new EditorTestServer(data)) {
      UUID player = UUID.randomUUID();
      String revoked = server.pair(new EditorTokens.Grant<>("", Optional.of(player), EditorScope.DEFAULT_PAIRING, List.of(),
          Duration.ofDays(7)));
      List<EditorScope> withCommands = new java.util.ArrayList<>(EditorScope.DEFAULT_PAIRING);
      withCommands.add(EditorScope.PUBLISH_COMMANDS);
      String narrowed = server.pair(new EditorTokens.Grant<>("", Optional.of(player), withCommands, List.of(),
          Duration.ofDays(7)));
      server.host.permissions.put(EditorPermissions.COMMANDS, EditorHost.Admin.NOT_ADMIN);

      server.service.api().orElseThrow().recheckAdmins();
      Thread.sleep(300L);
      JsonArray scopes = server.get("/v1/token", narrowed).body().getAsJsonArray("scopes");
      server.host.permissions.put(EditorPermissions.PAIR, EditorHost.Admin.NOT_ADMIN);
      server.service.api().orElseThrow().recheckAdmins();
      Thread.sleep(300L);

      assertFalse(scopes.toString().contains("publish:commands"));
      assertTrue(scopes.toString().contains("publish:world"));
      assertEquals("token-revoked", server.get("/v1/token", revoked).code());
    }
  }

  @Test
  void anUnconfirmedPlayerKeepsTheTokenForSevenDaysOnly() throws Exception {
    try (EditorTestServer server = new EditorTestServer(data)) {
      String token = server.pair(new EditorTokens.Grant<>("", Optional.of(UUID.randomUUID()), EditorScope.DEFAULT_PAIRING,
          List.of(), Duration.ofDays(30)));
      server.host.permissions.put(EditorPermissions.PAIR, EditorHost.Admin.UNKNOWN);

      server.service.api().orElseThrow().recheckAdmins();
      Thread.sleep(200L);
      int kept = server.get("/v1/token", token).status();
      server.clock.advance(Duration.ofDays(8));
      server.service.api().orElseThrow().recheckAdmins();
      Thread.sleep(300L);

      assertEquals(200, kept);
      assertEquals("token-revoked", server.get("/v1/token", token).code());
    }
  }

  @Test
  void aChangedPortRebindsTheListenerAndPairingsSurvive() throws Exception {
    try (EditorTestServer server = new EditorTestServer(data)) {
      String token = server.pair();
      int before = server.service.listenerPort();
      EditorSettings current = server.service.settings();
      EditorSettings next = new EditorSettings(current.enabled(), "Black Lodge", current.editorUrl(), true,
          current.listenAddress(), 0, "", current.trustedProxies(), "", "", current.allowedOrigins(), current.tokenDays(), current.requestBodyBytes(),
          current.concurrentRequestsPerToken(), current.requestsPerSecond(), current.pairAttemptsPerMinute(),
          current.openConnections() + 1, current.eventFramesPerSecond(), current.maxProjectMiB(), false,
          current.relayUrl(), 1, 2, current.relayFrameBytes(), current.relayConcurrentRequests());

      server.service.settings(next);

      assertNotEquals(-1, server.service.listenerPort());
      assertEquals(200, server.get("/v1/token", token).status());
      assertEquals("Black Lodge", server.get("/v1/hello", null).body().get("serverName").getAsString());
      assertTrue(before > 0);
    }
  }

  @Test
  void aRefusedOriginTellsThePlayerWhoMadeTheLink() throws Exception {
    try (EditorTestServer server = new EditorTestServer(data)) {
      server.link(new EditorTokens.Grant<>("", Optional.of(UUID.randomUUID()), EditorScope.DEFAULT_PAIRING, List.of(),
          Duration.ofDays(7)));
      server.send(HttpRequest.newBuilder(URI.create(server.base() + "/v1/hello")).header("Origin", "https://evil.example")
          .GET().build());

      assertTrue(server.host.notices.contains("Cooper's editor could not pair: origin https://evil.example is not in allowedOrigins"));
    }
  }

  @Test
  void aDocumentReadIsAnsweredWhileTheNotesAreBuilt() throws Exception {
    EditorWorkspaceFixtures.hologram(data, "welcome", "Welcome");
    EditorWorkspaceFixtures.NotesGate notes = new EditorWorkspaceFixtures.NotesGate();
    try (EditorTestServer server = new EditorTestServer(data, EditorTestServer.settings(true, false), notes)) {
      String token = server.pair();
      server.get("/v1/notes", token);
      EditorTestServer.Events events = server.events(token);
      events.next("open", Duration.ofSeconds(5));
      notes.armed = true;

      EditorWorkspaceFixtures.hologram(data, "welcome", "Changed outside Gloss");
      JsonObject catalog = events.next("catalog", Duration.ofSeconds(5));
      boolean building = notes.entered.await(5, TimeUnit.SECONDS);
      long started = System.nanoTime();
      EditorTestServer.Answer read = server.post("/v1/documents/read", token, refs("hologram", "welcome"), false);
      long millis = (System.nanoTime() - started) / 1_000_000L;
      notes.release.countDown();
      events.close();

      assertNotNull(catalog);
      assertTrue(building);
      assertEquals(200, read.status());
      assertTrue(read.body().toString().contains("Changed outside Gloss"), read.body().toString());
      assertTrue(millis < 500, "the read waited " + millis + " ms");
    }
  }

  @Test
  void aFileChangedOutsideGlossReachesStreamsWhileTheNotesAreBuilt() throws Exception {
    EditorWorkspaceFixtures.hologram(data, "welcome", "Welcome");
    EditorWorkspaceFixtures.hologram(data, "other", "Other");
    EditorWorkspaceFixtures.NotesGate notes = new EditorWorkspaceFixtures.NotesGate();
    try (EditorTestServer server = new EditorTestServer(data, EditorTestServer.settings(true, false), notes)) {
      String token = server.pair();
      server.get("/v1/notes", token);
      EditorTestServer.Events events = server.events(token);
      events.next("open", Duration.ofSeconds(5));
      notes.armed = true;
      EditorWorkspaceFixtures.hologram(data, "welcome", "Changed outside Gloss");
      events.next("catalog", Duration.ofSeconds(5));
      boolean building = notes.entered.await(5, TimeUnit.SECONDS);

      long started = System.nanoTime();
      EditorWorkspaceFixtures.hologram(data, "other", "Changed while the notes build");
      JsonObject catalog = events.next("catalog", Duration.ofSeconds(3));
      long millis = (System.nanoTime() - started) / 1_000_000L;
      notes.release.countDown();
      events.close();

      assertTrue(building);
      assertNotNull(catalog, "no catalog frame while the notes were built");
      assertEquals("other", catalog.getAsJsonArray("documents").get(0).getAsJsonObject().get("id").getAsString());
      assertTrue(millis < 1500, "the change took " + millis + " ms");
    }
  }

  private static JsonObject refs(String kind, String id) {
    JsonObject ref = new JsonObject();
    ref.addProperty("kind", kind);
    ref.addProperty("id", id);
    JsonArray refs = new JsonArray();
    refs.add(ref);
    JsonObject body = new JsonObject();
    body.add("refs", refs);
    return body;
  }

  @Test
  void aDeletedDocumentReachesOpenStreamsAsADeletion() throws Exception {
    EditorWorkspaceFixtures.hologram(data, "welcome", "Welcome");
    EditorWorkspaceFixtures.hologram(data, "gone", "Gone");
    try (EditorTestServer server = new EditorTestServer(data)) {
      EditorTestServer.Events events = server.events(server.pair());
      events.next("open", Duration.ofSeconds(5));

      Files.delete(data.resolve("holograms/gone.json"));
      JsonObject catalog = events.next("catalog", Duration.ofSeconds(4));
      while (catalog != null && !catalog.has("deleted")) {
        catalog = events.next("catalog", Duration.ofSeconds(4));
      }
      events.close();

      assertEquals("gone", catalog.getAsJsonArray("deleted").get(0).getAsJsonObject().get("id").getAsString());
      assertEquals("file", catalog.getAsJsonArray("deleted").get(0).getAsJsonObject().get("source").getAsString());
    }
  }
}
