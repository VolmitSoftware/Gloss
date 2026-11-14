package art.arcane.gloss.editor;

import art.arcane.gloss.editor.transport.EditorEventSink;
import art.arcane.gloss.editor.transport.EditorEventStream;
import art.arcane.gloss.editor.transport.EditorRequest;
import art.arcane.gloss.editor.transport.EditorResponse;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What refused browsers cost: rate windows by network, refused origins in the log and in chat. */
class EditorRefusalsTest {
  @TempDir
  Path data;

  private EditorTestServer server;
  private EditorApi api;

  @BeforeEach
  void start() throws Exception {
    server = new EditorTestServer(data);
    api = server.service.api().orElseThrow();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  @Test
  void pairingAttemptsFromOneIpv6NetworkShareAWindow() throws Exception {
    for (int index = 1; index <= 10; index++) {
      handle(relayPair("2001:db8:5:6::" + index));
    }

    EditorResponse sameNetwork = handle(relayPair("2001:db8:5:6::ff"));
    EditorResponse otherNetwork = handle(relayPair("2001:db8:5:7::1"));

    assertEquals("rate-limited", code(sameNetwork));
    assertEquals("pairing-invalid", code(otherNetwork));
  }

  @Test
  void eventStreamsStillOpenWhenEveryAddressWindowIsTaken() throws Exception {
    String token = server.pair();
    for (int index = 0; index < 4096; index++) {
      handle(relayPair("10.0." + (index >>> 8) + "." + (index & 0xff)));
    }
    for (int index = 0; index < 10; index++) {
      open("192.0.2." + index, "gls_tok_AAAAAAAAAAAAAAAA." + "A".repeat(43));
    }

    EditorEventStream stream = open("198.51.100.1", token);
    stream.onClose().run();

    assertEquals(200, stream.response().status());
  }

  @Test
  void refusedOriginsLogOneLineAMinute() throws Exception {
    for (int index = 0; index < 5; index++) {
      server.send(HttpRequest.newBuilder(URI.create(server.base() + "/v1/hello"))
          .header("Origin", "https://site" + index + ".example").GET().build());
    }
    long first = refusedLines();
    server.clock.advance(Duration.ofSeconds(61));
    server.send(HttpRequest.newBuilder(URI.create(server.base() + "/v1/hello"))
        .header("Origin", "https://late.example").GET().build());

    assertEquals(1L, first);
    assertEquals(2L, refusedLines());
  }

  @Test
  void refusedOriginsTellThePlayerWhoMadeTheLinkOnceAMinute() throws Exception {
    server.link(new EditorTokens.Grant("", Optional.of(UUID.randomUUID()), EditorScope.DEFAULT_PAIRING, List.of(),
        Duration.ofDays(7)));
    for (int index = 0; index < 3; index++) {
      server.send(HttpRequest.newBuilder(URI.create(server.base() + "/v1/hello"))
          .header("Origin", "https://site" + index + ".example").GET().build());
    }

    assertEquals(1L, server.host.notices.stream().filter((String line) -> line.contains("could not pair")).count());
  }

  @Test
  void aLineBreakInAnOriginOrAClientNameStaysOnOneLine() throws Exception {
    handle(new EditorRequest(EditorRequest.Transport.DIRECT, "GET", "/v1/hello",
        Map.of("Origin", "https://evil.example\nEditor paired as tok_forged", EditorReplies.PEER_HEADER, "127.0.0.1"),
        Optional.empty()));
    PairingCode code = server.link(EditorTestServer.grant(EditorScope.DEFAULT_PAIRING, List.of()));
    JsonObject body = new JsonObject();
    body.addProperty("tokenId", code.tokenId());
    body.addProperty("secret", code.secret());
    body.addProperty("client", "Chrome\nEditor paired as tok_forged");
    server.post("/v1/pair", null, body, false);

    List<String> lines = new ArrayList<>(server.host.warnings);
    lines.addAll(server.host.logs);
    lines.addAll(server.host.notices);
    assertTrue(lines.stream().anyMatch((String line) -> line.contains("evil.example")));
    assertTrue(lines.stream().anyMatch((String line) -> line.contains("from Chrome")));
    assertFalse(lines.stream().anyMatch((String line) -> line.contains("\n") || line.contains("\r")), lines.toString());
    assertFalse(api.parts().tokens().find(code.tokenId()).orElseThrow().client().orElseThrow().contains("\n"));
  }

  @Test
  void anOriginMatchesWhateverTheCaseOfItsSchemeAndHost() throws Exception {
    EditorTestServer.Answer answer = server.send(HttpRequest.newBuilder(URI.create(server.base() + "/v1/hello"))
        .header("Origin", "HTTP://127.0.0.1:8800").GET().build());

    assertEquals(200, answer.status());
  }

  private long refusedLines() {
    return server.host.warnings.stream().filter((String line) -> line.startsWith("Editor refused origin")).count();
  }

  private EditorResponse handle(EditorRequest request) throws Exception {
    return api.handle(request).toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private EditorEventStream open(String address, String token) throws Exception {
    EditorRequest request = new EditorRequest(EditorRequest.Transport.RELAY, "GET", "/v1/events",
        Map.of("X-Forwarded-For", address, "Authorization", "Bearer " + token), Optional.empty());
    EditorEventSink sink = new EditorEventSink() {
      @Override
      public boolean send(JsonObject event) {
        return true;
      }

      @Override
      public void close() {
      }
    };
    return api.openEvents(request, sink).toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private static EditorRequest relayPair(String address) {
    JsonObject body = new JsonObject();
    body.addProperty("tokenId", "tok_AAAAAAAAAAAA");
    body.addProperty("secret", "A".repeat(43));
    return new EditorRequest(EditorRequest.Transport.RELAY, "POST", "/v1/pair", Map.of("X-Forwarded-For", address),
        Optional.of(body));
  }

  private static String code(EditorResponse response) {
    JsonElement body = response.body().orElseThrow();
    return body.getAsJsonObject().getAsJsonObject("error").get("code").getAsString();
  }
}
