package art.arcane.gloss.editor.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EditorSyncHistoryTest {
  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  private HttpServer server;
  private ScheduledExecutorService clientExecutor;
  private ScheduledExecutorService deadlineExecutor;

  @After
  public void stop() {
    if (server != null) {
      server.stop(0);
    }
    if (clientExecutor != null) {
      clientExecutor.shutdownNow();
    }
    if (deadlineExecutor != null) {
      deadlineExecutor.shutdownNow();
    }
  }

  @Test
  public void aProjectCarriesTheVersionsTheServerHasForEachDocument() throws Exception {
    Path data = temp.newFolder("history-section").toPath();
    Files.createDirectories(data.resolve("menus"));
    Files.writeString(data.resolve("menus/shop.json"), "{\"components\":[]}",
        StandardCharsets.UTF_8);

    EditorSyncProject project = new EditorSyncContentSnapshotBuilder(data)
        .open(EditorSyncKind.WORKSPACE, "workspace", 1024 * 1024,
            new EditorSyncContentSnapshotBuilder.ServerSections() {
              @Override
              public JsonArray history(List<EditorSyncDocuments.Entry> documents) {
                JsonArray history = new JsonArray();
                history.add(EditorSyncProjectSections.historyEntry("menus", "shop", 1700L,
                    "watchdog", 18L));
                return history;
              }
            });

    JsonArray history = project.json().getAsJsonArray("history");
    assertEquals(1, history.size());
    assertEquals("menus", history.get(0).getAsJsonObject().get("kind").getAsString());
    assertEquals(1700L, history.get(0).getAsJsonObject().get("version").getAsLong());
    assertEquals(project.baseRevision(), EditorSyncJson.revision(project.json()));
  }

  @Test
  public void aPollThatOnlyCarriesHistoryRequestsIsAccepted() throws Exception {
    start(exchange -> respond(exchange, 200, """
        {"protocol":3,"sessionId":"session_id_123456789ab","publication":null,
         "historyRequests":[{"kind":"menus","id":"shop/main","version":1700}]}
        """));
    EditorSyncRelayClient client = client();

    EditorSyncRelayClient.RelayPoll poll = client.poll(session()).join();

    assertTrue(poll.publication().isEmpty());
    assertEquals(List.of(new EditorSyncRelayClient.HistoryRequest("menus", "shop/main", 1700L)),
        poll.historyRequests());
  }

  @Test
  public void answeringAHistoryRequestPostsTheStoredBytes() throws Exception {
    AtomicReference<JsonObject> body = new AtomicReference<>();
    AtomicReference<String> query = new AtomicReference<>();
    start(exchange -> {
      query.set(exchange.getRequestURI().getRawQuery());
      body.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
          StandardCharsets.UTF_8)).getAsJsonObject());
      respond(exchange, 200, "{\"protocol\":3,\"accepted\":true}");
    });

    client().answerHistory(session(),
        new EditorSyncRelayClient.HistoryRequest("menus", "shop/main", 1700L),
        "{\"components\":[]}").join();

    assertTrue(query.get(), query.get().contains("kind=menus"));
    assertTrue(query.get(), query.get().contains("documentId=shop%2Fmain"));
    assertTrue(query.get(), query.get().contains("version=1700"));
    assertEquals("{\"components\":[]}", body.get().get("json").getAsString());
    assertTrue(body.get().get("found").getAsBoolean());
  }

  @Test
  public void aVersionTheServerNoLongerHasIsAnsweredAsNotFound() throws Exception {
    AtomicReference<JsonObject> body = new AtomicReference<>();
    start(exchange -> {
      body.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
          StandardCharsets.UTF_8)).getAsJsonObject());
      respond(exchange, 200, "{\"protocol\":3,\"accepted\":true}");
    });

    client().answerHistory(session(),
        new EditorSyncRelayClient.HistoryRequest("menus", "shop", 1700L), null).join();

    assertEquals(false, body.get().get("found").getAsBoolean());
    assertTrue(body.get().toString(), body.get().get("json").isJsonNull());
  }

  private EditorSyncRelayClient client() {
    clientExecutor = Executors.newSingleThreadScheduledExecutor();
    deadlineExecutor = Executors.newSingleThreadScheduledExecutor();
    return new EditorSyncRelayClient(
        EditorSyncRelayClient.productionHttpClient(clientExecutor), deadlineExecutor,
        () -> 1024 * 1024, java.time.Duration.ofSeconds(20L));
  }

  private EditorSyncStoredSession session() {
    return new EditorSyncStoredSession(new EditorSyncStoredSession.Capability(
        "session_id_123456789ab", "server_token_123456789", endpoint()),
        new EditorSyncStoredSession.Subject(EditorSyncKind.MENU, "fixture"),
        Instant.now().plusSeconds(3600L), 0L,
        EditorSyncTestProjects.menuProject("fixture", "{\"components\":[]}"), null);
  }

  private String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v3";
  }

  private void start(Handler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v3/sessions", exchange -> {
      try {
        handler.handle(exchange);
      } finally {
        exchange.close();
      }
    });
    server.start();
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  @FunctionalInterface
  private interface Handler {
    void handle(HttpExchange exchange) throws IOException;
  }
}
