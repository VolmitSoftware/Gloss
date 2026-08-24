package art.arcane.gloss.editor.sync;

import java.util.regex.Pattern;

public enum EditorSyncKind {
  ANIMATION("animation"),
  BUBBLE_STYLE("bubble-style"),
  CONTAINER_PREVIEW("container-preview"),
  DAMAGE_INDICATORS("damage-indicators"),
  EMOJI("emoji"),
  HOLOGRAM("hologram"),
  MENU("menu"),
  MOTD("motd"),
  PANEL("panel"),
  REAL_DROPS("real-drops"),
  SCOREBOARD("scoreboard"),
  TABLIST("tablist"),
  WORKSPACE("workspace");

  public static final Pattern WIRE_KIND_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");

  private final String wireName;

  EditorSyncKind(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  public static EditorSyncKind parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException("kind must not be null");
    }
    if (!WIRE_KIND_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("kind must be a sync v3 slug: " + value);
    }
    for (EditorSyncKind kind : values()) {
      if (kind.wireName().equals(value)) {
        return kind;
      }
    }
    throw new IllegalArgumentException("unsupported Gloss sync subject kind: " + value);
  }
}
