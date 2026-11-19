package art.arcane.gloss.editor;

import art.arcane.gloss.editor.sync.EditorWorkspaceFixtures;
import art.arcane.volmlib.util.editor.EditorTokens;
import art.arcane.volmlib.util.editor.PairingCode;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** An editor service on a temporary data folder with its listener on a free loopback port, for tests. */
final class EditorTestServer implements AutoCloseable {
  static final String ORIGIN = "http://127.0.0.1:8800";

  /** A clock tests move by hand. */
  static final class MutableClock extends Clock {
    private volatile Instant now = Instant.parse("2026-11-13T10:00:00Z");

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }
  }

  /** A host that records what it was told. */
  static final class FakeHost implements EditorHost {
    final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    final List<String> warnings = Collections.synchronizedList(new ArrayList<>());
    final List<String> notices = Collections.synchronizedList(new ArrayList<>());
    final Map<String, Admin> permissions = new ConcurrentHashMap<>();

    @Override
    public String platform() {
      return "paper";
    }

    @Override
    public String minecraftVersion() {
      return "26.3";
    }

    @Override
    public String glossVersion() {
      return "3.3.0";
    }

    @Override
    public String serverName() {
      return "Twin Peaks";
    }

    @Override
    public String playerName(UUID player) {
      return "Cooper";
    }

    @Override
    public void log(String line) {
      logs.add(line);
    }

    @Override
    public void warn(String line) {
      warnings.add(line);
    }

    @Override
    public void permission(UUID player, String node, Consumer<Admin> result) {
      result.accept(permissions.getOrDefault(node, Admin.ADMIN));
    }

    @Override
    public void notice(Optional<UUID> player, String line) {
      notices.add(line);
    }
  }

  record Answer(int status, Map<String, List<String>> headers, JsonObject body) {
    String code() {
      return body.getAsJsonObject("error").get("code").getAsString();
    }

    Optional<String> header(String name) {
      return headers.entrySet().stream().filter((Map.Entry<String, List<String>> entry) -> entry.getKey()
          .equalsIgnoreCase(name)).findFirst().map((Map.Entry<String, List<String>> entry) -> entry.getValue().getFirst());
    }
  }

  final Path data;
  final MutableClock clock = new MutableClock();
  final FakeHost host = new FakeHost();
  final EditorService service;
  final HttpClient http = HttpClient.newHttpClient();
  private long counter;

  EditorTestServer(Path data) throws Exception {
    this(data, settings(true, false));
  }

  EditorTestServer(Path data, EditorSettings settings) throws Exception {
    this(data, settings, new EditorWorkspaceFixtures.NotesGate());
  }

  EditorTestServer(Path data, EditorSettings settings, EditorWorkspaceFixtures.NotesGate notes) throws Exception {
    this.data = data;
    this.service = new EditorService(data, host, settings,
        (EditorSettings ignored) -> EditorWorkspaceFixtures.detached(data, 64 * 1024 * 1024, notes), clock);
    service.start();
  }

  static EditorSettings settings(boolean listener, boolean relay) {
    return settings(listener, relay, "ws://127.0.0.1:9");
  }

  static EditorSettings settings(boolean listener, boolean relay, String relayUrl) {
    EditorSettings defaults = EditorSettings.DEFAULT;
    return new EditorSettings(true, "", "http://127.0.0.1:8800", listener, "127.0.0.1", 0, "", List.of(), "", "",
        List.of(ORIGIN), defaults.tokenDays(), defaults.requestBodyBytes(), defaults.concurrentRequestsPerToken(),
        defaults.requestsPerSecond(), defaults.pairAttemptsPerMinute(), defaults.openConnections(),
        defaults.eventFramesPerSecond(), 64, relay, relayUrl, 1, 2, 1024 * 1024, 64);
  }

  String base() {
    return "http://127.0.0.1:" + service.listenerPort() + "/gloss";
  }

  /** A pairing link's code for the grant. */
  PairingCode link(EditorTokens.Grant<EditorScope> grant) throws Exception {
    String link = service.pair(grant).link();
    return PairingCode.decode(EditorLink.PRODUCT, link.substring(link.indexOf("#/pair/") + "#/pair/".length()));
  }

  static EditorTokens.Grant<EditorScope> grant(List<EditorScope> scopes, List<String> folders) {
    return new EditorTokens.Grant<>("laptop", Optional.empty(), scopes, folders, Duration.ofDays(7));
  }

  /** Pairs with every default scope and returns the token. */
  String pair() throws Exception {
    return pair(grant(EditorScope.DEFAULT_PAIRING, List.of()));
  }

  String pair(EditorTokens.Grant<EditorScope> grant) throws Exception {
    PairingCode code = link(grant);
    JsonObject body = new JsonObject();
    body.addProperty("tokenId", code.tokenId());
    body.addProperty("secret", code.secret());
    body.addProperty("client", "Chrome on macOS");
    Answer answer = post("/v1/pair", null, body, false);
    if (answer.status() != 200) {
      throw new IllegalStateException("pairing failed: " + answer.body());
    }
    counter = answer.body().get("counter").getAsLong();
    return answer.body().get("token").getAsString();
  }

  Answer get(String path, String token) throws Exception {
    return get(path, token, Map.of());
  }

  Answer get(String path, String token, Map<String, String> headers) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path)).GET()
        .header("Origin", ORIGIN).timeout(Duration.ofSeconds(20));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    headers.forEach(request::header);
    return send(request.build());
  }

  Answer post(String path, String token, JsonObject body, boolean counted) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path))
        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).header("Origin", ORIGIN)
        .header("Content-Type", "application/json").timeout(Duration.ofSeconds(30));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    if (counted) {
      request.header("X-Gloss-Counter", Long.toString(++counter));
    }
    return send(request.build());
  }

  Answer send(HttpRequest request) throws Exception {
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    JsonElement parsed = response.body().isEmpty() ? new JsonObject() : JsonParser.parseString(response.body());
    return new Answer(response.statusCode(), response.headers().map(), parsed.getAsJsonObject());
  }

  /** An event stream on the direct path, collecting its frames. */
  Events events(String token) throws Exception {
    Events events = new Events();
    WebSocket socket = http.newWebSocketBuilder().header("Origin", ORIGIN)
        .buildAsync(URI.create("ws://127.0.0.1:" + service.listenerPort() + "/gloss/v1/events"), events)
        .get(10, TimeUnit.SECONDS);
    JsonObject auth = new JsonObject();
    auth.addProperty("type", "auth");
    auth.addProperty("token", token);
    socket.sendText(auth.toString(), true).get(5, TimeUnit.SECONDS);
    events.socket = socket;
    return events;
  }

  static final class Events implements WebSocket.Listener {
    final LinkedBlockingQueue<JsonObject> frames = new LinkedBlockingQueue<>();
    volatile int closeCode = -1;
    volatile String closeReason;
    WebSocket socket;
    private final StringBuilder text = new StringBuilder();

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      text.append(data);
      if (last) {
        frames.add(JsonParser.parseString(text.toString()).getAsJsonObject());
        text.setLength(0);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      closeCode = statusCode;
      closeReason = reason;
      return null;
    }

    /** The next frame of this type within the timeout, skipping others; null when none came. */
    JsonObject next(String type, Duration timeout) throws InterruptedException {
      long deadline = System.nanoTime() + timeout.toNanos();
      while (true) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          return null;
        }
        JsonObject frame = frames.poll(left, TimeUnit.NANOSECONDS);
        if (frame == null) {
          return null;
        }
        if (frame.get("type").getAsString().equals(type)) {
          return frame;
        }
      }
    }

    void close() {
      if (socket != null) {
        socket.abort();
      }
    }
  }

  @Override
  public void close() {
    service.close();
  }
}
