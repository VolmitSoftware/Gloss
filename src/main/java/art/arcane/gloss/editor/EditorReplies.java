package art.arcane.gloss.editor;

import art.arcane.gloss.editor.transport.EditorRequest;
import art.arcane.gloss.editor.transport.EditorResponse;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Error bodies with retry hints, header merging, the query string and the caller's address. */
public final class EditorReplies {
  /** Set by the direct listener from the socket; any copy a client sends is dropped first. */
  public static final String PEER_HEADER = "X-Gloss-Peer";

  private EditorReplies() {
  }

  public static EditorResponse error(EditorError error) {
    JsonObject body = new JsonObject();
    JsonObject detail = new JsonObject();
    detail.addProperty("code", error.code().wireName());
    String message = error.getMessage().isBlank() ? error.code().wireName() : error.getMessage();
    detail.addProperty("message", message.length() > 2048 ? message.substring(0, 2048) : message);
    if (error.retryAfterMillis() >= 0) {
      detail.addProperty("retryAfterMillis", error.retryAfterMillis());
    }
    JsonObject extra = error.extra();
    if (extra != null) {
      for (Map.Entry<String, JsonElement> field : extra.entrySet()) {
        detail.add(field.getKey(), field.getValue());
      }
    }
    body.add("error", detail);
    Map<String, String> headers = error.retryAfterMillis() >= 0
        ? Map.of("Retry-After", Long.toString(Math.max(1L, (error.retryAfterMillis() + 999L) / 1000L)))
        : Map.of();
    return new EditorResponse(error.code().status(), headers, Optional.of(body));
  }

  public static EditorResponse empty(int status) {
    return new EditorResponse(status, Map.of(), Optional.empty());
  }

  public static EditorResponse withHeaders(EditorResponse response, Map<String, String> extra) {
    Map<String, String> merged = new LinkedHashMap<>(response.headers());
    merged.putAll(extra);
    return new EditorResponse(response.status(), merged, response.body());
  }

  /** The decoded query string; repeated keys keep the first value. */
  public static Map<String, String> query(EditorRequest request) throws EditorError {
    Map<String, String> query = new LinkedHashMap<>();
    int question = request.path().indexOf('?');
    if (question < 0) {
      return query;
    }
    for (String pair : request.path().substring(question + 1).split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int equals = pair.indexOf('=');
      try {
        String key = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
        String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
        query.putIfAbsent(key, value);
      } catch (IllegalArgumentException invalid) {
        throw new EditorError(EditorError.Code.INVALID_REQUEST, "The query string is not URL encoded.");
      }
    }
    return query;
  }

  /** The browser's address: the socket's on the direct path, the relay's X-Forwarded-For on the relay. */
  public static String address(EditorRequest request) {
    String value = request.transport() == EditorRequest.Transport.DIRECT
        ? request.header(PEER_HEADER).orElse("unknown")
        : request.header("X-Forwarded-For").map((String list) -> list.split(",")[0].strip()).orElse("relay");
    return EditorNotices.printable(value.length() > 64 ? value.substring(0, 64) : value);
  }

  /** What rate windows and connection counts key an address by; anything but an IP literal stays as it is. */
  static String addressKey(String address) {
    return EditorSettings.literal(address).map(EditorReplies::addressKey).orElse(address);
  }

  /** An IPv4 address itself, an IPv6 address by its /64, since one host can usually use any address in it. */
  static String addressKey(InetAddress address) {
    if (!(address instanceof Inet6Address)) {
      return address.getHostAddress();
    }
    byte[] bytes = address.getAddress();
    StringBuilder prefix = new StringBuilder();
    for (int index = 0; index < 8; index += 2) {
      prefix.append(Integer.toHexString(((bytes[index] & 0xff) << 8) | (bytes[index + 1] & 0xff))).append(':');
    }
    return prefix.append(":/64").toString();
  }
}
