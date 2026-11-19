package art.arcane.gloss.editor;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code [editor]} in gloss.toml after the config file clamped and sanitized it: the editor API, its listener,
 * tokens, bounds and the relay client. A change to a bind key restarts only the listener, a change to a relay
 * key restarts only the relay client, and everything else applies to the next request.
 */
public record EditorSettings(boolean enabled, String serverName, String editorUrl, boolean listenerEnabled,
                             String listenAddress, int port, String advertisedUrl, List<String> trustedProxies,
                             String tlsCertificate, String tlsPrivateKey, List<String> allowedOrigins, int tokenDays,
                             int requestBodyBytes, int concurrentRequestsPerToken, int requestsPerSecond,
                             int pairAttemptsPerMinute, int openConnections, int eventFramesPerSecond,
                             int maxProjectMiB, boolean relayEnabled, String relayUrl, int relayReconnectMinSeconds,
                             int relayReconnectMaxSeconds, int relayFrameBytes, int relayConcurrentRequests) {
  public static final String EDITOR_URL = "https://gloss.volmitsoftware.com";
  public static final String RELAY_URL = "wss://gloss-relay-437930007071.us-central1.run.app";
  public static final int PORT = 9799;
  public static final List<String> ALLOWED_ORIGINS = List.of("https://gloss.volmitsoftware.com",
      "https://gloss-editor.web.app", "https://gloss-editor.firebaseapp.com");
  public static final EditorSettings DEFAULT = new EditorSettings(true, "", EDITOR_URL, true, "127.0.0.1", PORT, "",
      List.of(), "", "", ALLOWED_ORIGINS, 7, 4 * 1024 * 1024, 8, 20, 10, 64, 20, 64, true, RELAY_URL, 1, 60,
      1024 * 1024, 64);
  private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1", "[::1]");

  public EditorSettings {
    Objects.requireNonNull(serverName);
    Objects.requireNonNull(editorUrl);
    Objects.requireNonNull(listenAddress);
    Objects.requireNonNull(advertisedUrl);
    Objects.requireNonNull(tlsCertificate);
    Objects.requireNonNull(tlsPrivateKey);
    Objects.requireNonNull(relayUrl);
    trustedProxies = List.copyOf(trustedProxies);
    allowedOrigins = List.copyOf(allowedOrigins);
  }

  public static boolean loopback(String host) {
    return host != null && LOOPBACK.contains(host.toLowerCase(Locale.ROOT));
  }

  public boolean tls() {
    return !tlsCertificate.isEmpty() && !tlsPrivateKey.isEmpty();
  }

  /** True when {@code origin} is one of {@code allowedOrigins}; scheme and host compare without case. */
  public boolean allowsOrigin(String origin) {
    return origin(origin).filter(allowedOrigins::contains).isPresent();
  }

  /** {@code scheme://host[:port]} with scheme and host in lower case and a default port left out; empty for others. */
  public static Optional<String> origin(String text) {
    URI uri;
    try {
      uri = new URI(text.strip());
    } catch (URISyntaxException invalid) {
      return Optional.empty();
    }
    if (uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null
        || uri.getRawPath() != null && !uri.getRawPath().isEmpty() || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      return Optional.empty();
    }
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    int port = uri.getPort();
    boolean usual = port == -1 || port == 443 && scheme.equals("https") || port == 80 && scheme.equals("http");
    return Optional.of(scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (usual ? "" : ":" + port));
  }

  /** The relay's server path: {@code relayUrl} followed by {@code /agent}. */
  public URI relayAgentUri() {
    String base = relayUrl.endsWith("/") ? relayUrl.substring(0, relayUrl.length() - 1) : relayUrl;
    return URI.create(base + "/agent");
  }

  /** True when {@code next} needs a new listener socket. */
  public boolean rebind(EditorSettings next) {
    return listenerEnabled != next.listenerEnabled || !listenAddress.equals(next.listenAddress) || port != next.port
        || !tlsCertificate.equals(next.tlsCertificate) || !tlsPrivateKey.equals(next.tlsPrivateKey)
        || openConnections != next.openConnections;
  }

  /** True when {@code next} needs a new relay client. */
  public boolean relayChanged(EditorSettings next) {
    return relayEnabled != next.relayEnabled || !relayUrl.equals(next.relayUrl)
        || relayReconnectMinSeconds != next.relayReconnectMinSeconds
        || relayReconnectMaxSeconds != next.relayReconnectMaxSeconds || relayFrameBytes != next.relayFrameBytes
        || relayConcurrentRequests != next.relayConcurrentRequests;
  }
}
