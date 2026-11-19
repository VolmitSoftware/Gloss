package art.arcane.gloss.editor;

import art.arcane.gloss.editor.sync.EditorWorkspace;
import art.arcane.volmlib.util.editor.EditorIdentity;
import art.arcane.volmlib.util.editor.EditorRefusal;
import art.arcane.volmlib.util.editor.EditorTokens;
import art.arcane.volmlib.util.editor.transport.EditorEventSink;
import art.arcane.volmlib.util.editor.transport.EditorEventStream;
import art.arcane.volmlib.util.editor.transport.EditorRequest;
import art.arcane.volmlib.util.editor.transport.EditorRequestHandler;
import art.arcane.volmlib.util.editor.transport.EditorResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The transport-neutral {@code /gloss/v1} API. Every check lives here: origins, bearer tokens, scopes, folders,
 * {@code X-Gloss-Counter}, request rates, concurrency and body bounds. File work runs on the editor's file lane;
 * a full lane answers {@code busy}, and a calling thread never waits for files.
 */
public final class EditorApi implements EditorRequestHandler {
  public static final int PROTOCOL = 1;
  /** How long a token keeps working while the server cannot tell whether its player still holds the node. */
  public static final Duration UNCONFIRMED_ADMIN = Duration.ofDays(7);
  public static final String COUNTER_HEADER = EditorLink.PRODUCT.counterHeader();
  private static final Map<String, String> CORS = Map.of(
      "Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS",
      "Access-Control-Allow-Headers", "Authorization, Content-Type, X-Gloss-Counter, X-Gloss-Challenge, If-None-Match",
      "Access-Control-Expose-Headers", "ETag, Retry-After, X-Gloss-Server-Key, X-Gloss-Proof",
      "Access-Control-Max-Age", "600",
      "Access-Control-Allow-Private-Network", "true",
      "Vary", "Origin");

  /** What the API holds. */
  public record Parts(EditorSettings settings, EditorHost host, EditorIdentity identity, EditorTokens<EditorScope> tokens,
                      EditorEvents events, EditorWorkspace workspace, EditorWarnings warnings, EditorNotices notices,
                      EditorUploads uploads, Executor files, Clock clock) {
    public Parts with(EditorSettings next) {
      return new Parts(Objects.requireNonNull(next), host, identity, tokens, events, workspace, warnings, notices, uploads,
          files, clock);
    }
  }

  private volatile Parts parts;
  private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
  private final Map<String, double[]> buckets = new ConcurrentHashMap<>();
  private final EditorPairingRoutes pairing;
  private final EditorDocumentRoutes documents = new EditorDocumentRoutes(this);
  private final EditorPublishRoutes publish = new EditorPublishRoutes(this);

  public EditorApi(Parts parts) {
    this.parts = Objects.requireNonNull(parts);
    this.pairing = new EditorPairingRoutes(this);
  }

  public Parts parts() {
    return parts;
  }

  /** A settings change: origins, bounds and rates apply to the next request; tokens stay. */
  public void settings(EditorSettings next) {
    parts = parts.with(next);
  }

  @Override
  public CompletionStage<EditorResponse> handle(EditorRequest request) {
    CompletionStage<EditorResponse> answer;
    try {
      answer = route(request);
    } catch (EditorError refused) {
      answer = CompletableFuture.completedFuture(EditorReplies.error(refused));
    } catch (RuntimeException failure) {
      answer = CompletableFuture.completedFuture(internal(failure));
    }
    return answer.handle((EditorResponse response, Throwable failure) -> {
      EditorResponse result = response;
      if (failure != null) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        result = cause instanceof EditorError refused ? EditorReplies.error(refused) : internal(cause);
      }
      Map<String, String> cors = cors(request, request.method().equals("OPTIONS") || result.status() >= 400);
      return cors.isEmpty() ? result : EditorReplies.withHeaders(result, cors);
    });
  }

  EditorResponse internal(Throwable failure) {
    parts.host().warn("Editor request failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
    return EditorReplies.error(new EditorError(EditorError.Code.INTERNAL,
        "The server could not complete the request; see the server log."));
  }

  /** A head is checked before its body is read: the origin, and outside the open endpoints the token and scope. */
  @Override
  public Admission admit(EditorRequest head) {
    try {
      String method = head.method();
      String path = head.route();
      if (method.equals("OPTIONS")) {
        return Admission.OPEN;
      }
      origin(head);
      if (path.equals("/v1/hello") || path.equals("/v1/pair")) {
        return Admission.OPEN;
      }
      EditorTokens.Token<EditorScope> token = authenticate(bearer(head));
      Optional<Route> route = match(method, path.substring(4).split("/", -1));
      if (route.isPresent() && route.get().scope() != null && !token.has(route.get().scope())) {
        throw missing(route.get().scope());
      }
      return Admission.AUTHENTICATED;
    } catch (EditorError refused) {
      Map<String, String> cors = cors(head, true);
      EditorResponse response = EditorReplies.error(refused);
      return Admission.refused(cors.isEmpty() ? response : EditorReplies.withHeaders(response, cors));
    }
  }

  /**
   * CORS headers on the direct path. An allowed origin gets them on every answer. Any other origin gets them only on
   * a preflight and a refusal, so the browser can show why it was refused; every other request from it is refused.
   */
  private Map<String, String> cors(EditorRequest request, boolean preflightOrRefusal) {
    Optional<String> origin = request.header("origin");
    if (request.transport() != EditorRequest.Transport.DIRECT || origin.isEmpty()
        || (!preflightOrRefusal && !parts.settings().allowsOrigin(origin.get()))) {
      return Map.of();
    }
    Map<String, String> headers = new LinkedHashMap<>(CORS);
    headers.put("Access-Control-Allow-Origin", origin.get());
    return headers;
  }

  /**
   * The relay checks origins on its own browser handshake; the direct path checks every request. No Origin means no
   * browser. A refused origin is logged once an hour and told to whoever has a pairing link open, and in all at most
   * one line a minute and one notice a minute per link, since a client can send any number of origins.
   */
  private void origin(EditorRequest request) throws EditorError {
    if (request.transport() != EditorRequest.Transport.DIRECT) {
      return;
    }
    Optional<String> origin = request.header("origin");
    if (origin.isEmpty() || parts.settings().allowsOrigin(origin.get())) {
      return;
    }
    String shown = EditorNotices.printable(origin.get().length() > 128 ? origin.get().substring(0, 128) : origin.get());
    parts.warnings().once("origin " + shown, Duration.ofHours(1), "origins", EditorWarnings.MINUTE,
        "Editor refused origin " + shown + ": it is not in [editor] allowedOrigins");
    for (EditorTokens.Open<EditorScope> open : parts.tokens().openPairings()) {
      if (parts.warnings().due("origin notice " + shown + " " + open.tokenId(), Duration.ofHours(1),
          "origin notices " + open.tokenId(), EditorWarnings.MINUTE)) {
        parts.notices().refused(open, "origin " + shown + " is not in allowedOrigins");
      }
    }
    throw new EditorError(EditorError.Code.ORIGIN_NOT_ALLOWED, "Origin " + shown + " is not in [editor] allowedOrigins.");
  }

  private CompletionStage<EditorResponse> route(EditorRequest request) throws EditorError {
    String method = request.method();
    String path = request.route();
    if (method.equals("OPTIONS")) {
      return done(EditorReplies.empty(204));
    }
    origin(request);
    String[] segments = path.substring(4).split("/", -1);
    if (method.equals("GET") && path.equals("/v1/hello")) {
      return done(EditorResponse.json(200, pairing.hello()));
    }
    if (method.equals("POST") && path.equals("/v1/pair")) {
      return done(pairing.pair(request));
    }
    EditorTokens.Token<EditorScope> token = authenticate(bearer(request));
    Route route = match(method, segments).orElseThrow(() -> new EditorError(EditorError.Code.NOT_FOUND,
        "No endpoint " + method + " " + path + "."));
    if (route.scope() != null && !token.has(route.scope())) {
      throw missing(route.scope());
    }
    rate(token);
    if (route.counted()) {
      counter(request, token);
    }
    AtomicInteger running = inFlight.computeIfAbsent(token.id(), (String id) -> new AtomicInteger());
    if (running.incrementAndGet() > parts.settings().concurrentRequestsPerToken()) {
      running.decrementAndGet();
      parts.warnings().limited(token.id());
      throw new EditorError(EditorError.Code.RATE_LIMITED, "At most " + parts.settings().concurrentRequestsPerToken()
          + " requests run at once per token.", 250L, null);
    }
    CompletionStage<EditorResponse> result;
    try {
      result = route.handler().handle(request, token, segments);
    } catch (EditorError | RuntimeException refused) {
      running.decrementAndGet();
      throw refused;
    }
    return result.whenComplete((EditorResponse response, Throwable failure) -> running.decrementAndGet());
  }

  @FunctionalInterface
  interface Endpoint {
    CompletionStage<EditorResponse> handle(EditorRequest request, EditorTokens.Token<EditorScope> token, String[] segments)
        throws EditorError;
  }

  /** {@code counted}: a request that changes something and so needs {@code X-Gloss-Counter}. */
  private record Route(EditorScope scope, boolean counted, Endpoint handler) {
  }

  private Optional<Route> match(String method, String[] s) {
    String first = s[0];
    int n = s.length;
    Route route = switch (method) {
      case "GET" -> switch (first) {
        case "token" -> n == 1 ? new Route(null, false, this::token) : null;
        case "catalog" -> n == 1 ? new Route(EditorScope.READ, false, documents::catalog) : null;
        case "documents" -> n == 1 ? new Route(EditorScope.READ, false, documents::page)
            : n >= 3 ? new Route(EditorScope.READ, false, documents::document) : null;
        case "images" -> n == 2 ? new Route(EditorScope.READ, false, documents::image) : null;
        case "meta" -> n == 1 ? new Route(EditorScope.READ, false, documents::meta) : null;
        case "notes" -> n == 1 ? new Route(EditorScope.READ, false, documents::notes) : null;
        case "history" -> n == 1 ? new Route(EditorScope.HISTORY, false, documents::history) : null;
        default -> null;
      };
      case "POST" -> switch (first) {
        case "documents" -> n == 2 && s[1].equals("read") ? new Route(EditorScope.READ, false, documents::read) : null;
        case "images" -> n == 1 ? new Route(EditorScope.PUBLISH_IMAGES, false, publish::upload)
            : n == 2 && s[1].equals("read") ? new Route(EditorScope.READ, false, documents::images) : null;
        case "changes" -> n == 1 ? new Route(null, true, publish::publish) : null;
        default -> null;
      };
      default -> null;
    };
    return Optional.ofNullable(route);
  }

  private CompletionStage<EditorResponse> token(EditorRequest request, EditorTokens.Token<EditorScope> token, String[] segments) {
    JsonObject body = new JsonObject();
    body.addProperty("tokenId", token.id());
    body.addProperty("label", token.label());
    body.add("scopes", scopes(token.scopes()));
    if (!token.folders().isEmpty()) {
      body.add("folders", strings(token.folders()));
    }
    body.addProperty("expiresAt", token.expires().toString());
    body.addProperty("lifetimeSeconds", token.lifetime().toSeconds());
    body.addProperty("serverName", serverName());
    body.addProperty("fingerprint", parts.identity().fingerprint());
    return done(EditorResponse.json(200, body));
  }

  static EditorError missing(EditorScope scope) {
    JsonObject extra = new JsonObject();
    extra.addProperty("scope", scope.wireName());
    return new EditorError(EditorError.Code.SCOPE_MISSING, "This token lacks " + scope.wireName() + ".", -1L, extra);
  }

  static EditorError folderDenied(EditorTokens.Token<EditorScope> token, String what) {
    JsonObject extra = new JsonObject();
    extra.add("folders", strings(token.folders()));
    return new EditorError(EditorError.Code.FOLDER_DENIED, "This token cannot reach " + what + "; it is limited to folders "
        + String.join(", ", token.folders()) + ".", -1L, extra);
  }

  private static String bearer(EditorRequest request) throws EditorError {
    String header = request.header("authorization").orElseThrow(() -> new EditorError(EditorError.Code.UNAUTHORIZED,
        "Authorization: Bearer <token> is required."));
    if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      throw new EditorError(EditorError.Code.UNAUTHORIZED, "Authorization: Bearer <token> is required.");
    }
    return header.substring(7).strip();
  }

  private void counter(EditorRequest request, EditorTokens.Token<EditorScope> token) throws EditorError {
    String header = request.header(COUNTER_HEADER).orElseThrow(() -> new EditorError(EditorError.Code.INVALID_REQUEST,
        COUNTER_HEADER + " is required on this request."));
    long value;
    try {
      value = Long.parseLong(header.strip());
    } catch (NumberFormatException invalid) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, COUNTER_HEADER + " must be an integer.");
    }
    try {
      parts.tokens().advance(token.id(), value);
    } catch (EditorRefusal refused) {
      throw EditorError.of(refused);
    } catch (IOException failure) {
      throw new EditorError(EditorError.Code.INTERNAL, "The token store could not be written.");
    }
  }

  /** The token a bearer value names, or why it is refused. */
  private EditorTokens.Token<EditorScope> authenticate(String bearer) throws EditorError {
    try {
      return parts.tokens().authenticate(bearer);
    } catch (EditorRefusal refused) {
      throw EditorError.of(refused);
    }
  }

  /** Token bucket: requestsPerSecond, bursting to twice that. */
  private void rate(EditorTokens.Token<EditorScope> token) throws EditorError {
    double perSecond = parts.settings().requestsPerSecond();
    double capacity = perSecond * 2;
    double[] bucket = buckets.computeIfAbsent(token.id(), (String id) -> new double[] {capacity, System.nanoTime()});
    synchronized (bucket) {
      long now = System.nanoTime();
      bucket[0] = Math.min(capacity, bucket[0] + (now - bucket[1]) / 1e9 * perSecond);
      bucket[1] = now;
      if (bucket[0] < 1) {
        parts.warnings().limited(token.id());
        throw new EditorError(EditorError.Code.RATE_LIMITED, "Too many requests for this token.",
            (long) Math.ceil((1 - bucket[0]) / perSecond * 1000), null);
      }
      bucket[0] -= 1;
    }
  }

  /** {@code [editor] serverName}, else the server's MOTD, else its folder. */
  public String serverName() {
    return pairing.serverName();
  }

  static CompletionStage<EditorResponse> done(EditorResponse response) {
    return CompletableFuture.completedFuture(response);
  }

  @FunctionalInterface
  interface FileWork {
    EditorResponse run() throws EditorError, IOException;
  }

  /**
   * Runs on the file lane; a full lane answers busy at once. The token is checked again as the work starts, so a
   * token revoked or expired while its request waited neither reads nor writes.
   */
  CompletionStage<EditorResponse> files(EditorTokens.Token<EditorScope> token, FileWork work) throws EditorError {
    CompletableFuture<EditorResponse> result = new CompletableFuture<>();
    try {
      parts.files().execute(() -> {
        try {
          parts.tokens().stillValid(token.id());
          result.complete(work.run());
        } catch (EditorRefusal refused) {
          result.complete(EditorReplies.error(EditorError.of(refused)));
        } catch (EditorError refused) {
          result.complete(EditorReplies.error(refused));
        } catch (IOException | RuntimeException failure) {
          result.complete(internal(failure));
        }
      });
    } catch (RejectedExecutionException full) {
      throw new EditorError(EditorError.Code.BUSY, "The file lane is full.", 500L, null);
    }
    return result;
  }

  /**
   * {@code GET /v1/events}. The token comes in the Authorization header: the relay forwards the browser's, and the
   * direct listener copies it from the browser's first {@code auth} frame.
   */
  @Override
  public CompletionStage<EditorEventStream> openEvents(EditorRequest request, EditorEventSink sink) {
    String address = EditorReplies.address(request);
    try {
      origin(request);
      pairing.guard(address);
      EditorTokens.Token<EditorScope> token;
      try {
        token = authenticate(bearer(request));
      } catch (EditorError refused) {
        pairing.failed(address);
        throw refused;
      }
      if (!token.has(EditorScope.READ)) {
        throw missing(EditorScope.READ);
      }
      if (parts.events().open() >= parts.settings().openConnections()) {
        throw new EditorError(EditorError.Code.BUSY, "The server has its maximum of event streams open.", 5000L, null);
      }
      EditorWorkspace.View view = parts.workspace().view();
      JsonObject first = new JsonObject();
      first.addProperty("type", "open");
      first.addProperty("tokenId", token.id());
      first.addProperty("expiresAt", token.expires().toString());
      first.add("scopes", scopes(token.scopes()));
      first.addProperty("metaHash", view.metaHash());
      first.addProperty("notesHash", view.notesHash());
      Runnable remove = parts.events().add(token.id(), sink, token::reaches, first);
      AtomicBoolean closed = new AtomicBoolean();
      Map<String, String> cors = cors(request, false);
      EditorResponse opened = EditorReplies.empty(200);
      return CompletableFuture.completedFuture(new EditorEventStream(cors.isEmpty() ? opened
          : EditorReplies.withHeaders(opened, cors), () -> {
            if (closed.compareAndSet(false, true)) {
              remove.run();
            }
          }));
    } catch (EditorError refused) {
      parts.warnings().once("stream " + address + " " + refused.code().wireName(), EditorWarnings.MINUTE,
          "Editor refused an event stream from " + address + ": " + refused.code().wireName());
      return CompletableFuture.completedFuture(EditorEventStream.refused(EditorReplies.error(refused)));
    }
  }

  /**
   * Revokes tokens and links, closes their streams and tells the players who made them. A revoke the store could
   * not write still ends the streams, then throws to say it is lost if the server stops before a later write.
   */
  public List<String> revoke(String idOrAll, String reason, String cause) throws IOException {
    Map<String, EditorTokens.Token<EditorScope>> known = new HashMap<>();
    for (EditorTokens.Token<EditorScope> token : parts.tokens().list()) {
      known.put(token.id(), token);
    }
    List<String> ids;
    EditorTokens.Unsaved unsaved = null;
    try {
      ids = parts.tokens().revoke(idOrAll);
    } catch (EditorTokens.Unsaved failure) {
      ids = failure.ids();
      unsaved = failure;
    }
    for (String id : ids) {
      parts.events().close(id, reason);
      EditorTokens.Token<EditorScope> token = known.get(id);
      if (token != null) {
        parts.notices().revoked(id, token.label(), token.admin(), cause);
      }
    }
    if (unsaved != null) {
      throw new IOException("revoked until the server stops, but " + unsaved.getMessage()
          + "; Gloss tries again every second", unsaved);
    }
    return ids;
  }

  /** Called once a second by the service. */
  public void housekeeping() throws IOException {
    parts.warnings().tick(parts.settings());
    for (String expired : parts.tokens().expire()) {
      parts.events().close(expired, "token-expired");
    }
  }

  /**
   * Revokes tokens whose player lost {@code gloss.editor.pair}, or went unconfirmed for {@link #UNCONFIRMED_ADMIN}.
   * A token with {@code publish:commands} also needs {@code gloss.editor.commands}: losing it drops only that scope.
   */
  public void recheckAdmins() {
    for (EditorTokens.Token<EditorScope> token : parts.tokens().list()) {
      token.admin().ifPresent((UUID admin) -> parts.host().permission(admin, EditorPermissions.PAIR,
          (EditorHost.Admin status) -> {
            boolean stale = !token.adminChecked().plus(UNCONFIRMED_ADMIN).isAfter(parts.clock().instant());
            if (status == EditorHost.Admin.UNKNOWN && !stale) {
              return;
            }
            if (status != EditorHost.Admin.ADMIN) {
              String cause = status == EditorHost.Admin.UNKNOWN
                  ? "after " + UNCONFIRMED_ADMIN.toDays() + " days without confirming " + EditorPermissions.PAIR
                  : "after losing " + EditorPermissions.PAIR;
              onFiles(() -> revoke(token.id(), "token-revoked", cause));
              return;
            }
            if (!token.has(EditorScope.PUBLISH_COMMANDS)) {
              onFiles(() -> parts.tokens().confirmAdmin(token.id()));
              return;
            }
            parts.host().permission(admin, EditorPermissions.COMMANDS, (EditorHost.Admin commands) -> {
              if (commands == EditorHost.Admin.UNKNOWN && !stale) {
                return;
              }
              onFiles(() -> {
                if (commands != EditorHost.Admin.ADMIN) {
                  parts.tokens().dropScope(token.id(), EditorScope.PUBLISH_COMMANDS);
                }
                parts.tokens().confirmAdmin(token.id());
              });
            });
          }));
    }
  }

  @FunctionalInterface
  private interface TokenWork {
    void run() throws IOException;
  }

  private void onFiles(TokenWork work) {
    try {
      parts.files().execute(() -> {
        try {
          work.run();
        } catch (IOException failure) {
          parts.host().warn("Editor permission check failed: " + failure.getMessage());
        }
      });
    } catch (RejectedExecutionException full) {
      // The next minute retries.
    }
  }

  /** Ends every stream at shutdown. */
  public void close() {
    parts.events().closeAll("server-stopping");
  }

  static JsonArray scopes(List<EditorScope> scopes) {
    JsonArray array = new JsonArray();
    scopes.forEach((EditorScope scope) -> array.add(scope.wireName()));
    return array;
  }

  static JsonArray strings(List<String> values) {
    JsonArray array = new JsonArray();
    values.forEach(array::add);
    return array;
  }

  /** The request's JSON object body. */
  static JsonObject body(EditorRequest request) throws EditorError {
    JsonElement body = request.body().orElseThrow(() -> new EditorError(EditorError.Code.INVALID_REQUEST,
        "This request needs a JSON body."));
    if (!body.isJsonObject()) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, "The body must be one JSON object.");
    }
    return body.getAsJsonObject();
  }

  /** Refuses fields outside {@code allowed}. */
  static void fields(JsonObject object, Set<String> allowed) throws EditorError {
    for (String key : object.keySet()) {
      if (!allowed.contains(key)) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "Unknown field " + key + ".");
      }
    }
  }

  static String string(JsonObject object, String field, Pattern pattern) throws EditorError {
    JsonElement value = object.get(field);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
        || !pattern.matcher(value.getAsString()).matches()) {
      throw new EditorError(EditorError.Code.INVALID_REQUEST, field + " is missing or malformed.");
    }
    return value.getAsString();
  }
}
