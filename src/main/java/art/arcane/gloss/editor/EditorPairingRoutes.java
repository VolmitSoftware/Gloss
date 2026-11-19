package art.arcane.gloss.editor;

import art.arcane.volmlib.util.editor.EditorRefusal;
import art.arcane.volmlib.util.editor.EditorTokens;
import art.arcane.volmlib.util.editor.PairingRate;
import art.arcane.volmlib.util.editor.transport.EditorRequest;
import art.arcane.volmlib.util.editor.transport.EditorResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The open endpoints {@code GET /v1/hello} and {@code POST /v1/pair}, the per-address pairing rate, and what a
 * failed pairing tells the console and the player who made the link.
 */
final class EditorPairingRoutes {
  private static final Pattern TOKEN_ID = Pattern.compile("tok_[A-Za-z0-9_-]{8,60}");
  private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9_-]{43}");
  private final EditorApi api;
  private final PairingRate rate;

  EditorPairingRoutes(EditorApi api) {
    this.api = api;
    this.rate = new PairingRate(api.parts().clock(), () -> api.parts().settings().pairAttemptsPerMinute());
  }

  JsonObject hello() {
    EditorApi.Parts parts = api.parts();
    JsonObject body = new JsonObject();
    body.addProperty("protocol", EditorApi.PROTOCOL);
    JsonArray protocols = new JsonArray();
    protocols.add(EditorApi.PROTOCOL);
    body.add("protocols", protocols);
    body.addProperty("fingerprint", parts.identity().fingerprint());
    String version = parts.host().glossVersion();
    body.addProperty("gloss", version.length() > 64 ? version.substring(0, 64) : version);
    body.addProperty("platform", parts.host().platform());
    String minecraft = parts.host().minecraftVersion();
    body.addProperty("minecraft", minecraft.matches("\\d+\\.\\d+(\\.\\d+)?") ? minecraft : "0.0");
    body.addProperty("serverName", serverName());
    return body;
  }

  String serverName() {
    String configured = api.parts().settings().serverName();
    return EditorServerName.bounded(configured.isBlank() ? api.parts().host().serverName() : configured);
  }

  EditorResponse pair(EditorRequest request) throws EditorError {
    EditorApi.Parts parts = api.parts();
    rate(EditorReplies.address(request));
    JsonObject body = EditorApi.body(request);
    EditorApi.fields(body, Set.of("tokenId", "secret", "client"));
    String tokenId = EditorApi.string(body, "tokenId", TOKEN_ID);
    String secret = EditorApi.string(body, "secret", SECRET);
    Optional<String> client = Optional.empty();
    JsonElement clientValue = body.get("client");
    if (clientValue != null) {
      if (!clientValue.isJsonPrimitive() || !clientValue.getAsJsonPrimitive().isString()
          || clientValue.getAsString().length() > 128) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "client: at most 128 characters of text.");
      }
      client = clientValue.getAsString().isBlank() ? Optional.empty()
          : Optional.of(EditorNotices.printable(clientValue.getAsString().strip()));
    }
    Optional<EditorTokens.Open<EditorScope>> waiting = parts.tokens().pairing(tokenId);
    Map.Entry<String, EditorTokens.Token<EditorScope>> issued;
    try {
      issued = parts.tokens().exchange(tokenId, secret, client);
    } catch (IOException failure) {
      throw new EditorError(EditorError.Code.INTERNAL, "The token store could not be written.");
    } catch (EditorRefusal refusal) {
      EditorError refused = EditorError.of(refusal);
      String address = EditorReplies.address(request);
      if (refused.code() == EditorError.Code.PAIRING_EXPIRED) {
        parts.warnings().once("pair " + tokenId, Duration.ofHours(1), "Editor pairing " + tokenId
            + " failed: the link expired before it was opened; run /gloss editor pair again");
        waiting.ifPresent((EditorTokens.Open<EditorScope> open) -> parts.notices().refused(open, "the link expired; make a new one"));
      } else {
        String why = switch (refused.code()) {
          case PAIRING_USED -> "the link was already used";
          case PAIRING_REVOKED -> "the link was revoked";
          default -> "the link is unknown";
        };
        parts.warnings().once("pair unknown " + address, Duration.ofMinutes(1), "Editor pairing from " + address
            + " failed: " + why);
      }
      throw refused;
    }
    EditorTokens.Token<EditorScope> token = issued.getValue();
    parts.notices().paired(token);
    JsonObject response = new JsonObject();
    response.addProperty("token", issued.getKey());
    response.addProperty("tokenId", token.id());
    response.add("scopes", EditorApi.scopes(token.scopes()));
    if (!token.folders().isEmpty()) {
      JsonArray folders = new JsonArray();
      token.folders().forEach(folders::add);
      response.add("folders", folders);
    }
    response.addProperty("expiresAt", token.expires().toString());
    response.addProperty("lifetimeSeconds", token.lifetime().toSeconds());
    response.addProperty("fingerprint", parts.identity().fingerprint());
    response.addProperty("counter", token.counter());
    token.admin().ifPresent((UUID admin) -> response.addProperty("admin", admin.toString()));
    response.addProperty("serverName", serverName());
    return EditorResponse.json(200, response);
  }

  /** Counts a pairing attempt against the address's window and refuses it past {@code pairAttemptsPerMinute}. */
  void rate(String address) throws EditorError {
    try {
      rate.pairing(address);
    } catch (EditorRefusal refused) {
      throw EditorError.of(refused);
    }
  }

  /** Refuses an event stream from an address whose window is used up, before its token is checked. */
  void guard(String address) throws EditorError {
    try {
      rate.guard(address);
    } catch (EditorRefusal refused) {
      throw EditorError.of(refused);
    }
  }

  /** Counts an event stream whose token was refused against the address's window. */
  void failed(String address) {
    rate.failed(address);
  }
}
