package art.arcane.gloss.editor;

import art.arcane.gloss.editor.transport.EditorRequest;
import art.arcane.gloss.editor.transport.EditorResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The open endpoints {@code GET /v1/hello} and {@code POST /v1/pair}, the per-address pairing rate, and what a
 * failed pairing tells the console and the player who made the link.
 */
final class EditorPairingRoutes {
  private static final int PAIR_WINDOW_ADDRESSES = 4096;
  private static final String SHARED_PAIR_WINDOW = "";
  private static final Pattern TOKEN_ID = Pattern.compile("tok_[A-Za-z0-9_-]{8,60}");
  private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9_-]{43}");
  private final EditorApi api;
  private final Map<String, long[]> pairWindows = new ConcurrentHashMap<>();

  EditorPairingRoutes(EditorApi api) {
    this.api = api;
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
    Optional<EditorTokens.Open> waiting = parts.tokens().pairing(tokenId);
    Map.Entry<String, EditorTokens.Token> issued;
    try {
      issued = parts.tokens().exchange(tokenId, secret, client);
    } catch (IOException failure) {
      throw new EditorError(EditorError.Code.INTERNAL, "The token store could not be written.");
    } catch (EditorError refused) {
      String address = EditorReplies.address(request);
      if (refused.code() == EditorError.Code.PAIRING_EXPIRED) {
        parts.warnings().once("pair " + tokenId, Duration.ofHours(1), "Editor pairing " + tokenId
            + " failed: the link expired before it was opened; run /gloss editor pair again");
        waiting.ifPresent((EditorTokens.Open open) -> parts.notices().refused(open, "the link expired; make a new one"));
      } else {
        parts.warnings().once("pair unknown " + address, Duration.ofMinutes(1), "Editor pairing from " + address
            + " failed: the link is unknown, used or revoked");
      }
      throw refused;
    }
    EditorTokens.Token token = issued.getValue();
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
    long[] window = window(address, true);
    synchronized (window) {
      if (++window[1] > api.parts().settings().pairAttemptsPerMinute()) {
        throw limited();
      }
    }
  }

  /** Refuses an event stream from an address whose window is used up, before its token is checked. */
  void guard(String address) throws EditorError {
    long[] window = window(address, false);
    if (window == null) {
      return;
    }
    synchronized (window) {
      if (window[1] >= api.parts().settings().pairAttemptsPerMinute()) {
        throw limited();
      }
    }
  }

  /** Counts an event stream whose token was refused against the address's window. */
  void failed(String address) {
    long[] window = window(address, false);
    if (window == null) {
      return;
    }
    synchronized (window) {
      window[1]++;
    }
  }

  /**
   * This minute's window of the address, IPv6 by its /64. Past {@link #PAIR_WINDOW_ADDRESSES} addresses a new one
   * shares one window when {@code shared} allows; event streams never do, so pairing floods cannot lock them out.
   */
  private long[] window(String address, boolean shared) {
    String key = EditorReplies.addressKey(address);
    long minute = api.parts().clock().millis() / 60_000L;
    long[] window = pairWindows.get(key);
    if (window == null) {
      if (pairWindows.size() >= PAIR_WINDOW_ADDRESSES) {
        pairWindows.entrySet().removeIf((Map.Entry<String, long[]> entry) -> entry.getValue()[0] != minute);
      }
      if (pairWindows.size() >= PAIR_WINDOW_ADDRESSES) {
        if (!shared) {
          return null;
        }
        key = SHARED_PAIR_WINDOW;
      }
      window = pairWindows.computeIfAbsent(key, (String unused) -> new long[] {minute, 0L});
    }
    synchronized (window) {
      if (window[0] != minute) {
        window[0] = minute;
        window[1] = 0L;
      }
    }
    return window;
  }

  /** Attempts count per clock minute, so the wait is what is left of this one; the message states it. */
  private EditorError limited() {
    long wait = 60_000L - api.parts().clock().millis() % 60_000L;
    return new EditorError(EditorError.Code.RATE_LIMITED, "Too many pairing attempts from this address. Try again in "
        + (wait + 999L) / 1000L + " s.", wait, null);
  }
}
