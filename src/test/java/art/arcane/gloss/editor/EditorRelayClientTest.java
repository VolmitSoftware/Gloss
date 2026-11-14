package art.arcane.gloss.editor;

import art.arcane.gloss.editor.relay.EditorRelayClient;
import art.arcane.gloss.editor.transport.EditorEventSink;
import art.arcane.gloss.editor.transport.EditorEventStream;
import art.arcane.gloss.editor.transport.EditorRequest;
import art.arcane.gloss.editor.transport.EditorRequestHandler;
import art.arcane.gloss.editor.transport.EditorResponse;
import art.arcane.gloss.editor.sync.EditorWorkspaceFixtures;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorRelayClientTest {
  @TempDir
  Path data;

  @Test
  void theRelayConnectsAtTheFirstPairingRegistersAndCarriesTheApi() throws Exception {
    EditorWorkspaceFixtures.hologram(data, "welcome", "Welcome");
    try (FakeRelay relay = new FakeRelay();
         EditorTestServer server = new EditorTestServer(data, EditorTestServer.settings(false, true, relay.url()))) {
      assertTrue(relay.received.isEmpty());
      assertEquals("relay waits for the first pairing", server.service.status().getFirst().split(" \\| ")[2]);

      PairingCode code = server.link(EditorTestServer.grant(EditorScope.DEFAULT_PAIRING, List.of()));
      String nonce = "N".repeat(43);
      JsonObject challenge = new JsonObject();
      challenge.addProperty("type", "challenge");
      challenge.addProperty("protocol", 1);
      challenge.addProperty("nonce", nonce);
      relay.send(challenge);
      JsonObject register = relay.next("register");
      JsonObject registered = new JsonObject();
      registered.addProperty("type", "registered");
      registered.addProperty("fingerprint", code.fingerprint());
      relay.send(registered);

      assertEquals("/agent", relay.path);
      assertTrue(code.relayUrl().orElseThrow().equals(relay.url()));
      assertTrue(code.directUrl().isEmpty());
      assertEquals(code.serverKey(), register.get("serverKey").getAsString());
      assertTrue(verify(code.serverKey(), EditorRelayClient.CHALLENGE_PREFIX + nonce,
          register.get("signature").getAsString()));

      String browserChallenge = "C".repeat(43);
      JsonObject hello = request(1, "GET", "/v1/hello", null);
      hello.getAsJsonObject("headers").addProperty("X-Gloss-Challenge", browserChallenge);
      relay.send(hello);
      JsonObject helloAnswer = relay.next("response");
      JsonObject proofHeaders = helloAnswer.getAsJsonObject("headers");
      assertEquals(200, helloAnswer.get("status").getAsInt());
      assertEquals(code.serverKey(), proofHeaders.get("X-Gloss-Server-Key").getAsString());
      assertTrue(verify(code.serverKey(), EditorRelayClient.PROOF_PREFIX + browserChallenge,
          proofHeaders.get("X-Gloss-Proof").getAsString()));

      JsonObject exchange = new JsonObject();
      exchange.addProperty("tokenId", code.tokenId());
      exchange.addProperty("secret", code.secret());
      relay.send(request(2, "POST", "/v1/pair", exchange));
      JsonObject paired = relay.next("response");
      String token = paired.getAsJsonObject("body").get("token").getAsString();
      assertEquals(2, paired.get("id").getAsInt());

      JsonObject stream = request(3, "GET", "/v1/events", null);
      stream.getAsJsonObject("headers").addProperty("Authorization", "Bearer " + token);
      relay.send(stream);
      JsonObject opened = relay.next("response");
      JsonObject first = relay.next("event");
      assertEquals(200, opened.get("status").getAsInt());
      assertEquals("open", first.getAsJsonObject("event").get("type").getAsString());

      JsonObject catalog = request(4, "GET", "/v1/catalog", null);
      catalog.getAsJsonObject("headers").addProperty("Authorization", "Bearer " + token);
      relay.send(catalog);
      JsonObject listed = relay.next("response");
      assertEquals(1, listed.getAsJsonObject("body").getAsJsonArray("documents").size());
      assertTrue(server.service.relayRegistered());

      server.service.revoke("all");
      JsonObject closing = relay.next("event");
      JsonObject closed = relay.next("close");
      assertEquals("closing", closing.getAsJsonObject("event").get("type").getAsString());
      assertEquals(3, closed.get("id").getAsInt());
    }
  }

  @Test
  void aRelayAnswerOverTheFrameLimitIsRefusedAsTooLarge() throws Exception {
    for (int index = 0; index < 40; index++) {
      EditorWorkspaceFixtures.menu(data, "big/m" + index, "{\"title\":\"" + "x".repeat(30_000) + "\",\"components\":[]}");
    }
    try (FakeRelay relay = new FakeRelay();
         EditorTestServer server = new EditorTestServer(data, EditorTestServer.settings(false, true, relay.url()))) {
      PairingCode code = server.link(EditorTestServer.grant(EditorScope.DEFAULT_PAIRING, List.of()));
      JsonObject challenge = new JsonObject();
      challenge.addProperty("type", "challenge");
      challenge.addProperty("protocol", 1);
      challenge.addProperty("nonce", "N".repeat(43));
      relay.send(challenge);
      relay.next("register");
      JsonObject registered = new JsonObject();
      registered.addProperty("type", "registered");
      registered.addProperty("fingerprint", code.fingerprint());
      relay.send(registered);
      JsonObject exchange = new JsonObject();
      exchange.addProperty("tokenId", code.tokenId());
      exchange.addProperty("secret", code.secret());
      relay.send(request(1, "POST", "/v1/pair", exchange));
      String token = relay.next("response").getAsJsonObject("body").get("token").getAsString();

      JsonObject page = request(2, "GET", "/v1/documents?maxBytes=4194304", null);
      page.getAsJsonObject("headers").addProperty("Authorization", "Bearer " + token);
      relay.send(page);
      JsonObject answer = relay.next("response");

      assertEquals(200, answer.get("status").getAsInt());
      assertTrue(answer.toString().getBytes(StandardCharsets.UTF_8).length < 1024 * 1024);
      assertFalse(answer.getAsJsonObject("body").get("next").isJsonNull());
    }
  }

  @Test
  void aQuietRelayThatAnswersPingsKeepsTheConnection() throws Exception {
    try (FakeRelay relay = new FakeRelay();
         EditorTestServer server = new EditorTestServer(data, EditorTestServer.settings(false, false))) {
      relay.answerPings = true;
      EditorRelayClient client = registered(relay, server, Duration.ofMillis(900));
      Thread.sleep(2500L);
      boolean registered = client.isRegistered();
      int connections = relay.connections.get();
      client.close();

      assertTrue(registered);
      assertEquals(1, connections);
    }
  }

  @Test
  void aHalfOpenRelayIsLeftAndDialledAgain() throws Exception {
    try (FakeRelay relay = new FakeRelay();
         EditorTestServer server = new EditorTestServer(data, EditorTestServer.settings(false, false))) {
      EditorRelayClient client = registered(relay, server, Duration.ofMillis(900));
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (relay.connections.get() < 2 && System.nanoTime() < deadline) {
        Thread.sleep(20L);
      }
      int connections = relay.connections.get();
      client.close();

      assertEquals(2, connections);
    }
  }

  @Test
  void anEventQueuedBeforeTheStreamIsAnsweredFollowsTheAnswer() throws Exception {
    StreamHandler handler = new StreamHandler(true);
    try (FakeRelay relay = new FakeRelay()) {
      EditorRelayClient client = registered(relay, handler);
      try {
        relay.send(request(5, "GET", "/v1/events", null));
        JsonObject answer = relay.next("response");
        JsonObject first = relay.next("event");

        assertEquals(200, answer.get("status").getAsInt());
        assertEquals(5, first.get("id").getAsInt());
        assertEquals("open", first.getAsJsonObject("event").get("type").getAsString());
      } finally {
        client.close();
      }
    }
  }

  @Test
  void anEventOverTheFrameLimitClosesTheStream() throws Exception {
    StreamHandler handler = new StreamHandler(false);
    try (FakeRelay relay = new FakeRelay()) {
      EditorRelayClient client = registered(relay, handler);
      try {
        relay.send(request(7, "GET", "/v1/events", null));
        relay.next("response");
        JsonObject huge = new JsonObject();
        huge.addProperty("type", "catalog");
        huge.addProperty("padding", "x".repeat(1024 * 1024));

        boolean sent = handler.sink.get(5, TimeUnit.SECONDS).send(huge);
        JsonObject closed = relay.next("close");

        assertFalse(sent);
        assertEquals(7, closed.get("id").getAsInt());
        assertTrue(handler.closed.get());
      } finally {
        client.close();
      }
    }
  }

  @Test
  void aRequestThatBlocksDoesNotHoldUpTheNext() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    EditorRequestHandler handler = new EditorRequestHandler() {
      @Override
      public CompletionStage<EditorResponse> handle(EditorRequest request) {
        if (request.path().endsWith("slow")) {
          try {
            release.await(15, TimeUnit.SECONDS);
          } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
          }
        }
        return CompletableFuture.completedFuture(EditorResponse.json(200, new JsonObject()));
      }

      @Override
      public CompletionStage<EditorEventStream> openEvents(EditorRequest request, EditorEventSink sink) {
        return CompletableFuture.completedFuture(EditorEventStream.refused(EditorResponse.error(404, "not-found", "")));
      }
    };
    try (FakeRelay relay = new FakeRelay()) {
      EditorRelayClient client = registered(relay, handler);
      try {
        relay.send(request(1, "GET", "/v1/hello?slow", null));
        relay.send(request(2, "GET", "/v1/hello", null));
        JsonObject first = relay.next("response");
        release.countDown();
        JsonObject second = relay.next("response");

        assertEquals(2, first.get("id").getAsInt());
        assertEquals(1, second.get("id").getAsInt());
      } finally {
        release.countDown();
        client.close();
      }
    }
  }

  /** Opens every stream; {@code early} sends the first event before the stream is answered, as a frame tick can. */
  private static final class StreamHandler implements EditorRequestHandler {
    final CompletableFuture<EditorEventSink> sink = new CompletableFuture<>();
    final AtomicBoolean closed = new AtomicBoolean();
    private final boolean early;

    StreamHandler(boolean early) {
      this.early = early;
    }

    @Override
    public CompletionStage<EditorResponse> handle(EditorRequest request) {
      return CompletableFuture.completedFuture(EditorResponse.json(200, new JsonObject()));
    }

    @Override
    public CompletionStage<EditorEventStream> openEvents(EditorRequest request, EditorEventSink opened) {
      if (early) {
        JsonObject open = new JsonObject();
        open.addProperty("type", "open");
        opened.send(open);
      }
      sink.complete(opened);
      return CompletableFuture.completedFuture(new EditorEventStream(EditorResponse.json(200, new JsonObject()),
          () -> closed.set(true)));
    }
  }

  /** A relay client with a new identity answering through {@code handler}, registered with {@code relay}. */
  private static EditorRelayClient registered(FakeRelay relay, EditorRequestHandler handler) throws Exception {
    EditorIdentity identity = EditorIdentity.generate();
    EditorRelayClient client = new EditorRelayClient(EditorTestServer.settings(false, true, relay.url()), identity,
        handler, (String line) -> { }, (String line) -> { });
    client.start();
    JsonObject challenge = new JsonObject();
    challenge.addProperty("type", "challenge");
    challenge.addProperty("protocol", 1);
    challenge.addProperty("nonce", "N".repeat(43));
    relay.send(challenge);
    relay.next("register");
    JsonObject registered = new JsonObject();
    registered.addProperty("type", "registered");
    registered.addProperty("fingerprint", identity.fingerprint());
    relay.send(registered);
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!client.isRegistered() && System.nanoTime() < deadline) {
      Thread.sleep(10L);
    }
    assertTrue(client.isRegistered());
    return client;
  }

  /** A relay client of the server's identity, registered with {@code relay}, that gives up after {@code idle}. */
  private static EditorRelayClient registered(FakeRelay relay, EditorTestServer server, Duration idle) throws Exception {
    EditorApi api = server.service.api().orElseThrow();
    EditorRelayClient client = new EditorRelayClient(EditorTestServer.settings(false, true, relay.url()),
        api.parts().identity(), api, (String line) -> { }, (String line) -> { }, Duration.ofSeconds(10), idle);
    client.start();
    JsonObject challenge = new JsonObject();
    challenge.addProperty("type", "challenge");
    challenge.addProperty("protocol", 1);
    challenge.addProperty("nonce", "N".repeat(43));
    relay.send(challenge);
    relay.next("register");
    JsonObject registered = new JsonObject();
    registered.addProperty("type", "registered");
    registered.addProperty("fingerprint", api.parts().identity().fingerprint());
    relay.send(registered);
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!client.isRegistered() && System.nanoTime() < deadline) {
      Thread.sleep(10L);
    }
    assertTrue(client.isRegistered());
    return client;
  }

  private static JsonObject request(int id, String method, String path, JsonObject body) {
    JsonObject frame = new JsonObject();
    frame.addProperty("type", "request");
    frame.addProperty("id", id);
    frame.addProperty("method", method);
    frame.addProperty("path", path);
    JsonObject headers = new JsonObject();
    headers.addProperty("Origin", "https://gloss.volmitsoftware.com");
    headers.addProperty("X-Forwarded-For", "203.0.113.9");
    frame.add("headers", headers);
    if (body != null) {
      frame.add("body", body);
    }
    return frame;
  }

  private static boolean verify(String serverKey, String text, String signature) throws Exception {
    byte[] raw = Base64.getUrlDecoder().decode(serverKey);
    byte[] spki = HexFormat.of().parseHex("302a300506032b6570032100");
    byte[] encoded = new byte[spki.length + raw.length];
    System.arraycopy(spki, 0, encoded, 0, spki.length);
    System.arraycopy(raw, 0, encoded, spki.length, raw.length);
    PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    Signature verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(key);
    verifier.update(text.getBytes(StandardCharsets.UTF_8));
    return verifier.verify(Base64.getUrlDecoder().decode(signature));
  }
}
