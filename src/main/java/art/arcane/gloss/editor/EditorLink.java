package art.arcane.gloss.editor;

import art.arcane.gloss.doc.AtomicFiles;
import art.arcane.volmlib.util.editor.EditorIdentity;
import art.arcane.volmlib.util.editor.EditorProduct;
import art.arcane.volmlib.util.editor.EditorTokens;
import art.arcane.volmlib.util.editor.ListenerSettings;
import art.arcane.volmlib.util.editor.StrictJson;
import art.arcane.volmlib.util.editor.relay.RelaySettings;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Gloss's side of the shared editor link: its wire names, the files under {@code editor/} and its token file layout
 * ({@code folders}, and {@code ended} entries that say whether a token expired or was revoked).
 */
public final class EditorLink {
  public static final EditorProduct PRODUCT = new EditorProduct("Gloss", "gls_", "GLS1.", "/gloss editor pair", true);
  public static final String TOKENS_PATH = "editor/tokens.json";
  public static final String IDENTITY_PATH = "editor/identity.json";
  public static final EditorTokens.Format<EditorScope> FORMAT = new TokenFormat();
  private static final Pattern FOLDER = Pattern.compile("[a-z0-9_-]{1,64}(/[a-z0-9_-]{1,64}){0,7}");

  private EditorLink() {
  }

  /** A folder a pairing may be limited to, such as {@code shop} or {@code shop/sub}. */
  public static boolean validFolder(String folder) {
    return folder != null && FOLDER.matcher(folder).matches();
  }

  public static EditorTokens<EditorScope> tokens(Path data, Clock clock, Consumer<String> warn) throws IOException {
    return new EditorTokens<>(PRODUCT, data.resolve(TOKENS_PATH), TOKENS_PATH, FORMAT, AtomicFiles::replaceDurable, clock, warn);
  }

  public static EditorIdentity identity(Path data) throws IOException {
    return EditorIdentity.loadOrCreate(data.resolve(IDENTITY_PATH), IDENTITY_PATH, AtomicFiles::replaceDurable);
  }

  public static ListenerSettings listener(EditorSettings settings) {
    return new ListenerSettings(settings.listenAddress(), settings.port(), settings.tlsCertificate(), settings.tlsPrivateKey(),
        settings.openConnections(), settings.trustedProxies(), settings.requestBodyBytes(), "", 0);
  }

  public static RelaySettings relay(EditorSettings settings) {
    return new RelaySettings(settings.relayEnabled(), settings.relayUrl(), settings.relayReconnectMinSeconds(),
        settings.relayReconnectMaxSeconds(), settings.relayFrameBytes(), settings.relayConcurrentRequests());
  }

  private static final class TokenFormat implements EditorTokens.Format<EditorScope> {
    @Override
    public EditorTokens.Stored<EditorScope> decode(String json) throws IOException {
      JsonObject saved = StrictJson.object(json);
      List<EditorTokens.Token<EditorScope>> tokens = new ArrayList<>();
      for (JsonElement element : saved.getAsJsonArray("tokens")) {
        JsonObject entry = element.getAsJsonObject();
        List<EditorScope> scopes = new ArrayList<>();
        for (JsonElement scope : entry.getAsJsonArray("scopes")) {
          scopes.add(EditorScope.parse(scope.getAsString())
              .orElseThrow(() -> new IllegalArgumentException("unknown scope " + scope)));
        }
        List<String> folders = new ArrayList<>();
        for (JsonElement folder : entry.getAsJsonArray("folders")) {
          folders.add(folder.getAsString());
        }
        Instant created = Instant.parse(entry.get("created").getAsString());
        tokens.add(new EditorTokens.Token<>(entry.get("id").getAsString(), entry.get("hash").getAsString(),
            entry.get("label").getAsString(), optional(entry, "admin").map(UUID::fromString), scopes, folders, created,
            Instant.parse(entry.get("expires").getAsString()), Duration.ofSeconds(entry.get("lifetimeSeconds").getAsLong()),
            optional(entry, "lastUsed").map(Instant::parse), entry.get("counter").getAsLong(), optional(entry, "client"),
            optional(entry, "adminChecked").map(Instant::parse).orElse(created)));
      }
      List<EditorTokens.Ended> ended = new ArrayList<>();
      if (saved.has("ended")) {
        for (JsonElement element : saved.getAsJsonArray("ended")) {
          JsonObject entry = element.getAsJsonObject();
          ended.add(new EditorTokens.Ended(entry.get("id").getAsString(), Instant.parse(entry.get("expires").getAsString()),
              entry.get("expired").getAsBoolean()));
        }
      }
      return new EditorTokens.Stored<>(tokens, ended);
    }

    @Override
    public String encode(EditorTokens.Stored<EditorScope> stored) {
      JsonObject saved = new JsonObject();
      saved.addProperty("schemaVersion", 1);
      JsonArray list = new JsonArray();
      for (EditorTokens.Token<EditorScope> token : stored.tokens()) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", token.id());
        entry.addProperty("hash", token.hash());
        entry.addProperty("label", token.label());
        token.admin().ifPresent((UUID admin) -> entry.addProperty("admin", admin.toString()));
        JsonArray scopes = new JsonArray();
        token.scopes().forEach((EditorScope scope) -> scopes.add(scope.wireName()));
        entry.add("scopes", scopes);
        JsonArray folders = new JsonArray();
        token.folders().forEach(folders::add);
        entry.add("folders", folders);
        entry.addProperty("created", token.created().toString());
        entry.addProperty("expires", token.expires().toString());
        entry.addProperty("lifetimeSeconds", token.lifetime().toSeconds());
        token.lastUsed().ifPresent((Instant used) -> entry.addProperty("lastUsed", used.toString()));
        entry.addProperty("counter", token.counter());
        token.client().ifPresent((String client) -> entry.addProperty("client", client));
        entry.addProperty("adminChecked", token.adminChecked().toString());
        list.add(entry);
      }
      saved.add("tokens", list);
      JsonArray gone = new JsonArray();
      for (EditorTokens.Ended end : stored.ended()) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", end.id());
        entry.addProperty("expires", end.expires().toString());
        entry.addProperty("expired", end.expired());
        gone.add(entry);
      }
      saved.add("ended", gone);
      return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(saved) + "\n";
    }

    private static Optional<String> optional(JsonObject entry, String key) {
      JsonElement value = entry.get(key);
      return value == null || value.isJsonNull() ? Optional.empty() : Optional.of(value.getAsString());
    }
  }
}
