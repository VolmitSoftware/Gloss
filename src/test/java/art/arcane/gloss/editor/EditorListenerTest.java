package art.arcane.gloss.editor;

import art.arcane.volmlib.util.editor.EditorListener;
import art.arcane.volmlib.util.editor.PairingCode;
import art.arcane.volmlib.util.editor.transport.EditorEventSink;
import art.arcane.volmlib.util.editor.transport.EditorEventStream;
import art.arcane.volmlib.util.editor.transport.EditorRequest;
import art.arcane.volmlib.util.editor.transport.EditorRequestHandler;
import art.arcane.volmlib.util.editor.transport.EditorResponse;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EditorListenerTest {
  private static final EditorRequestHandler HELLO = new EditorRequestHandler() {
    @Override
    public CompletionStage<EditorResponse> handle(EditorRequest request) {
      return CompletableFuture.completedFuture(EditorResponse.json(200, new JsonObject()));
    }

    @Override
    public CompletionStage<EditorEventStream> openEvents(EditorRequest request, EditorEventSink sink) {
      return CompletableFuture.completedFuture(EditorEventStream.refused(EditorResponse.error(401, "unauthorized", "")));
    }
  };

  @TempDir
  Path data;

  @Test
  void aTrustedProxyCountsPairingAttemptsUnderTheForwardedClient() throws Exception {
    try (EditorTestServer server = new EditorTestServer(data, settings("127.0.0.1", List.of("127.0.0.1"), "", "", 64))) {
      for (int index = 0; index < 10; index++) {
        pairFrom(server, "203.0.113.5");
      }

      assertEquals("rate-limited", pairFrom(server, "203.0.113.5").code());
      assertEquals("pairing-invalid", pairFrom(server, "203.0.113.6").code());
    }
  }

  @Test
  void anUntrustedPeerCannotChooseTheAddressItIsCountedUnder() throws Exception {
    try (EditorTestServer server = new EditorTestServer(data)) {
      for (int index = 0; index < 10; index++) {
        pairFrom(server, "203.0.113.5");
      }

      assertEquals("rate-limited", pairFrom(server, "203.0.113.6").code());
    }
  }

  @Test
  void aBodyThatRepeatsAKeyIsRefusedAsInvalid() throws Exception {
    try (EditorTestServer server = new EditorTestServer(data)) {
      EditorTestServer.Answer repeated = pairWith(server, "{\"tokenId\":\"tok_aaaaaaaaaaaa\",\"tokenId\":\"tok_bbbbbbbbbbbb\","
          + "\"secret\":\"" + "A".repeat(43) + "\"}");

      assertEquals("400 invalid-request", repeated.status() + " " + repeated.code());
    }
  }

  @Test
  void aBodyInLenientJsonIsRefusedAsInvalid() throws Exception {
    String secret = "A".repeat(43);
    List<String> bodies = List.of(
        "{tokenId:\"tok_aaaaaaaaaaaa\",secret:\"" + secret + "\"}",
        "{'tokenId':'tok_aaaaaaaaaaaa','secret':'" + secret + "'}",
        "{\"tokenId\":\"tok_aaaaaaaaaaaa\",/* note */\"secret\":\"" + secret + "\"}");
    try (EditorTestServer server = new EditorTestServer(data)) {
      List<String> answers = new ArrayList<>();
      for (String body : bodies) {
        EditorTestServer.Answer answer = pairWith(server, body);
        answers.add(answer.status() + " " + answer.code());
      }

      assertEquals(List.of("400 invalid-request", "400 invalid-request", "400 invalid-request"), answers);
    }
  }

  @Test
  void aTrustedProxyMayHoldMoreConnectionsThanOneAddress() throws Exception {
    try (EditorTestServer server = new EditorTestServer(data, settings("127.0.0.1", List.of("127.0.0.1"), "", "", 64))) {
      List<Socket> idle = new ArrayList<>();
      try {
        for (int index = 0; index <= EditorListener.CONNECTIONS_PER_ADDRESS; index++) {
          idle.add(new Socket(InetAddress.getLoopbackAddress(), server.service.listenerPort()));
        }

        assertEquals(200, server.get("/v1/hello", null).status());
      } finally {
        for (Socket socket : idle) {
          socket.close();
        }
      }
    }
  }

  @Test
  void aListenerOnTheIpv6LoopbackAdvertisesLocalhost() throws Exception {
    try (EditorTestServer server = new EditorTestServer(data, settings("::1", List.of(), "", "", 64))) {
      assumeTrue(server.service.listenerPort() > 0, "this host has no IPv6 loopback");

      PairingCode code = server.link(EditorTestServer.grant(EditorScope.DEFAULT_PAIRING, List.of()));

      assertEquals("http://localhost:" + server.service.listenerPort(), code.directUrl().orElseThrow());
    }
  }

  @Test
  void aPortAnotherServerHoldsIsNamedWithWhatToChange() throws Exception {
    try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
         EditorTestServer server = new EditorTestServer(data,
             settings("127.0.0.1", taken.getLocalPort(), List.of(), "", "", 64))) {
      String line = server.host.warnings.stream().filter((String warning) -> warning.startsWith("Editor listener failed"))
          .findFirst().orElseThrow();

      assertEquals(-1, server.service.listenerPort());
      assertTrue(line.contains("cannot bind 127.0.0.1:" + taken.getLocalPort()), line);
      assertTrue(line.contains("set [editor] port to a free port"), line);
    }
  }

  @Test
  void aConnectionPastTheTlsLimitIsClosedWithoutAHandshake() throws Exception {
    EditorListener listener = tlsListener(1);
    try (Socket ignored = new Socket(InetAddress.getLoopbackAddress(), listener.port());
         Socket refused = new Socket(InetAddress.getLoopbackAddress(), listener.port())) {
      refused.setSoTimeout(5000);
      long started = System.nanoTime();
      int read = readOrEnd(refused.getInputStream());
      long millis = (System.nanoTime() - started) / 1_000_000L;

      assertEquals(-1, read);
      assertTrue(millis < 1000L, "closed after " + millis + " ms");
    } finally {
      listener.close();
    }
  }

  @Test
  void aTlsHandshakeMustFinishWithinItsDeadlineHoweverSlowlyItTrickles() throws Exception {
    EditorListener listener = tlsListener(4);
    try (Socket slow = new Socket(InetAddress.getLoopbackAddress(), listener.port())) {
      CompletableFuture<Long> closed = closedAt(slow);
      long started = System.nanoTime();
      byte[] header = {0x16, 0x03, 0x01, 0x02, 0x00};
      OutputStream output = slow.getOutputStream();
      for (int index = 0; !closed.isDone() && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(14); index++) {
        try {
          output.write(index < header.length ? header[index] : 0);
          output.flush();
        } catch (IOException gone) {
          break;
        }
        Thread.sleep(500L);
      }
      long millis = (closed.get(5, TimeUnit.SECONDS) - started) / 1_000_000L;

      assertTrue(millis < 12_000L, "closed after " + millis + " ms");
    } finally {
      listener.close();
    }
  }

  @Test
  void theAuthFrameMustArriveWithinItsDeadlineHoweverOftenTheBrowserPings() throws Exception {
    EditorListener listener = new EditorListener(EditorLink.PRODUCT, EditorLink.listener(settings("127.0.0.1", List.of(), "", "", 8)), data, HELLO,
        (String line) -> { });
    listener.start();
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.port())) {
      OutputStream output = socket.getOutputStream();
      output.write(("GET /gloss/v1/events HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
          + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
          .getBytes(StandardCharsets.ISO_8859_1));
      output.flush();
      InputStream input = socket.getInputStream();
      assertTrue(head(input).startsWith("HTTP/1.1 101"));
      CompletableFuture<Integer> closeCode = new CompletableFuture<>();
      Thread.ofVirtual().start(() -> closeCode.complete(closeCode(input)));
      long started = System.nanoTime();
      while (!closeCode.isDone() && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(14)) {
        try {
          output.write(new byte[] {(byte) 0x89, (byte) 0x80, 1, 2, 3, 4});
          output.flush();
        } catch (IOException gone) {
          break;
        }
        Thread.sleep(1000L);
      }
      int code = closeCode.get(5, TimeUnit.SECONDS);
      long millis = (System.nanoTime() - started) / 1_000_000L;

      assertEquals(4401, code);
      assertTrue(millis < 12_000L, "closed after " + millis + " ms");
    } finally {
      listener.close();
    }
  }

  private EditorListener tlsListener(int connections) throws Exception {
    certificate();
    EditorListener listener = new EditorListener(EditorLink.PRODUCT,
        EditorLink.listener(settings("127.0.0.1", List.of(), "cert.pem", "key.pem", connections)), data, HELLO, (String line) -> { });
    listener.start();
    return listener;
  }

  /** A self-signed EC certificate for localhost and its PKCS#8 key, as cert.pem and key.pem in the data folder. */
  private void certificate() throws Exception {
    Path store = data.resolve("tls.p12");
    Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
        "-genkeypair", "-alias", "editor", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost",
        "-validity", "2", "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "changeit")
        .redirectErrorStream(true).start();
    keytool.getInputStream().readAllBytes();
    assertEquals(0, keytool.waitFor());
    KeyStore keys = KeyStore.getInstance("PKCS12");
    try (InputStream input = Files.newInputStream(store)) {
      keys.load(input, "changeit".toCharArray());
    }
    PrivateKey key = (PrivateKey) keys.getKey("editor", "changeit".toCharArray());
    Files.writeString(data.resolve("cert.pem"), pem("CERTIFICATE", keys.getCertificate("editor").getEncoded()));
    Files.writeString(data.resolve("key.pem"), pem("PRIVATE KEY", key.getEncoded()));
  }

  private static String pem(String type, byte[] der) {
    return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
        + "\n-----END " + type + "-----\n";
  }

  static EditorSettings settings(String listenAddress, List<String> trustedProxies, String certificate, String key,
                                 int openConnections) {
    return settings(listenAddress, 0, trustedProxies, certificate, key, openConnections);
  }

  static EditorSettings settings(String listenAddress, int port, List<String> trustedProxies, String certificate,
                                 String key, int openConnections) {
    EditorSettings base = EditorTestServer.settings(true, false);
    return new EditorSettings(true, "", base.editorUrl(), true, listenAddress, port, "", trustedProxies, certificate, key,
        base.allowedOrigins(), base.tokenDays(), base.requestBodyBytes(), base.concurrentRequestsPerToken(),
        base.requestsPerSecond(), base.pairAttemptsPerMinute(), openConnections, base.eventFramesPerSecond(),
        base.maxProjectMiB(), false, base.relayUrl(), 1, 2, base.relayFrameBytes(), base.relayConcurrentRequests());
  }

  private static EditorTestServer.Answer pairFrom(EditorTestServer server, String client) throws Exception {
    JsonObject body = new JsonObject();
    body.addProperty("tokenId", "tok_AAAAAAAAAAAA");
    body.addProperty("secret", "A".repeat(43));
    return server.send(HttpRequest.newBuilder(URI.create(server.base() + "/v1/pair"))
        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).header("Origin", EditorTestServer.ORIGIN)
        .header("Content-Type", "application/json").header("X-Forwarded-For", client).build());
  }

  private static EditorTestServer.Answer pairWith(EditorTestServer server, String body) throws Exception {
    return server.send(HttpRequest.newBuilder(URI.create(server.base() + "/v1/pair"))
        .POST(HttpRequest.BodyPublishers.ofString(body)).header("Origin", EditorTestServer.ORIGIN)
        .header("Content-Type", "application/json").build());
  }

  /** When the peer closes the socket: the end of its stream or a reset. */
  private static CompletableFuture<Long> closedAt(Socket socket) {
    CompletableFuture<Long> closed = new CompletableFuture<>();
    Thread.ofVirtual().start(() -> {
      try {
        InputStream input = socket.getInputStream();
        int read = 0;
        while (read >= 0) {
          read = input.read();
        }
      } catch (IOException gone) {
        // A reset ends the stream too.
      }
      closed.complete(System.nanoTime());
    });
    return closed;
  }

  private static int readOrEnd(InputStream input) {
    try {
      return input.read();
    } catch (IOException reset) {
      return -1;
    }
  }

  private static String head(InputStream input) throws IOException {
    ByteArrayOutputStream head = new ByteArrayOutputStream();
    while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
      int read = input.read();
      if (read < 0) {
        break;
      }
      head.write(read);
    }
    return head.toString(StandardCharsets.ISO_8859_1);
  }

  /** Reads server frames until a close and returns its code; -1 when the socket ends without one. */
  private static int closeCode(InputStream input) {
    try {
      while (true) {
        int first = input.read();
        int length = input.read();
        if (first < 0 || length < 0) {
          return -1;
        }
        byte[] payload = input.readNBytes(length & 0x7f);
        if ((first & 0x0f) == 0x8) {
          return payload.length >= 2 ? ((payload[0] & 0xff) << 8) | (payload[1] & 0xff) : -1;
        }
      }
    } catch (IOException gone) {
      return -1;
    }
  }
}
