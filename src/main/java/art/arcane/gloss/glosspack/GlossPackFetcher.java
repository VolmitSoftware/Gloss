package art.arcane.gloss.glosspack;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Downloads a {@code .glosspack} over HTTPS. Only HTTPS is accepted, redirects are refused, and the
 * body is capped at the same {@value GlossPackArchive#MAX_BYTES} bytes the archive reader enforces.
 */
public final class GlossPackFetcher {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10L);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30L);

    private final HttpClient http;

    public GlossPackFetcher() {
        this(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    GlossPackFetcher(HttpClient http) {
        this.http = Objects.requireNonNull(http, "http");
    }

    public static boolean isUrl(String source) {
        String normalized = source == null ? "" : source.strip().toLowerCase(Locale.ROOT);
        return normalized.startsWith("https://") || normalized.startsWith("http://");
    }

    public byte[] fetch(String url) throws IOException, InterruptedException {
        URI uri = URI.create(Objects.requireNonNull(url, "url").strip());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("pack downloads must use HTTPS without credentials");
        }
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "Gloss-Packs/1")
                .GET()
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("pack download returned HTTP " + response.statusCode());
        }
        byte[] body = response.body();
        if (body.length > GlossPackArchive.MAX_BYTES) {
            throw new IOException("pack download exceeds " + GlossPackArchive.MAX_BYTES + " bytes");
        }
        return body;
    }
}
