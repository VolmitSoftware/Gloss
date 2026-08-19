package art.arcane.gloss.editor.sync;

import java.util.Locale;
import java.util.regex.Pattern;

public enum EditorSyncKind {
  MENU,
  PANEL;

  /**
   * Sync v2 wire kinds are open slugs; this pattern is the shared contract with
   * the editor. Gloss maps the slugs it has a codec for onto this enum and
   * rejects the rest with an actionable error.
   */
  public static final Pattern WIRE_KIND_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");

  public String wireName() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static EditorSyncKind parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException("kind must not be null");
    }
    if (!WIRE_KIND_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("kind must be a sync v2 slug: " + value);
    }
    for (EditorSyncKind kind : values()) {
      if (kind.wireName().equals(value)) {
        return kind;
      }
    }
    throw new IllegalArgumentException("this Gloss build cannot sync '" + value
        + "' subjects; update Gloss");
  }
}
