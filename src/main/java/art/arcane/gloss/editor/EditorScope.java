package art.arcane.gloss.editor;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Editor token scopes, in the order they are shown. Publishing is split by the editor's six library groups
 * plus images; {@link #PUBLISH_COMMANDS} is never a default.
 */
public enum EditorScope {
  READ("read", "See documents and images"),
  HISTORY("history", "See stored versions"),
  PUBLISH_SCREENS("publish:screens", "Publish menus and screens"),
  PUBLISH_HUD("publish:hud", "Publish HUD and server list"),
  PUBLISH_CHAT("publish:chat", "Publish chat"),
  PUBLISH_WORLD("publish:world", "Publish world displays"),
  PUBLISH_PLAYERS("publish:players", "Publish player labels"),
  PUBLISH_SHARED("publish:shared", "Publish shared resources"),
  PUBLISH_IMAGES("publish:images", "Upload and delete images"),
  /** Documents that run console commands, which amounts to console access. Pairing it needs gloss.editor.commands. */
  PUBLISH_COMMANDS("publish:commands", "Publish documents that run console commands");

  /** What {@code /gloss editor pair} grants unless {@code scopes=} narrows it. */
  public static final List<EditorScope> DEFAULT_PAIRING = Arrays.stream(values())
      .filter((EditorScope scope) -> scope != PUBLISH_COMMANDS)
      .toList();

  private final String wireName;
  private final String words;

  EditorScope(String wireName, String words) {
    this.wireName = wireName;
    this.words = words;
  }

  public String wireName() {
    return wireName;
  }

  /** The scope in plain words, as the pairing card shows it. */
  public String words() {
    return words;
  }

  public static Optional<EditorScope> parse(String wireName) {
    for (EditorScope scope : values()) {
      if (scope.wireName.equals(wireName)) {
        return Optional.of(scope);
      }
    }
    return Optional.empty();
  }

  /** Every scope name, for refusals and completion. */
  public static List<String> names() {
    return Arrays.stream(values()).map(EditorScope::wireName).toList();
  }

  /**
   * The scope that publishes a document kind, grouped as the editor's library groups them
   * ({@code gloss-web/lib/config/kind_groups.dart}). Empty for a kind this build does not know.
   */
  public static Optional<EditorScope> forKind(String kind) {
    EditorScope scope = switch (kind) {
      case "menu", "container-preview", "inventory", "panel" -> PUBLISH_SCREENS;
      case "scoreboard", "tablist", "surface", "motd", "leaderboard" -> PUBLISH_HUD;
      case "channel", "connections", "emoji", "bubble-style" -> PUBLISH_CHAT;
      case "hologram", "marker", "waypoint", "damage-indicators", "real-drops", "entity-overlays" -> PUBLISH_WORLD;
      case "nameplate", "nametag" -> PUBLISH_PLAYERS;
      case "animation", "strings", "names", "glyph", "behavior", "presets" -> PUBLISH_SHARED;
      default -> null;
    };
    return Optional.ofNullable(scope);
  }
}
