package art.arcane.gloss.editor;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

public final class EditorUrl {
  private EditorUrl() {
  }

  public static String base(String editorUrl) {
    URI parsed;
    try {
      parsed = new URI(Objects.requireNonNull(editorUrl, "editorUrl"));
    } catch (URISyntaxException failure) {
      throw new IllegalArgumentException("editorUrl must be a valid HTTP(S) URL", failure);
    }
    String scheme = parsed.getScheme();
    if (scheme == null || parsed.getHost() == null || parsed.getUserInfo() != null
        || !(scheme.toLowerCase(Locale.ROOT).equals("http")
        || scheme.toLowerCase(Locale.ROOT).equals("https"))) {
      throw new IllegalArgumentException("editorUrl must be a valid HTTP(S) URL");
    }
    String path = parsed.getPath();
    if (path == null || path.isEmpty()) {
      path = "/";
    } else if (!path.endsWith("/")) {
      path += "/";
    }
    try {
      return new URI(parsed.getScheme().toLowerCase(Locale.ROOT), null,
          parsed.getHost().toLowerCase(Locale.ROOT), parsed.getPort(), path, null, null)
          .toASCIIString();
    } catch (URISyntaxException failure) {
      throw new IllegalArgumentException("editorUrl could not be normalized", failure);
    }
  }
}
