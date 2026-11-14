package art.arcane.gloss.editor;

import java.nio.file.Path;
import java.util.regex.Pattern;

/** The name the editor shows for a server: {@code [editor] serverName}, else {@link #fallback}. */
public final class EditorServerName {
  static final String VANILLA_MOTD = "A Minecraft Server";
  static final String UNNAMED = "Minecraft server";
  private static final int LIMIT = 64;
  private static final Pattern CODES = Pattern.compile("(?i)[&§]#[0-9a-f]{6}|[&§][0-9a-fk-orx]|<[^<>]{1,64}>");

  private EditorServerName() {
  }

  /** The MOTD's first line without colour codes, unless it is empty or the vanilla default; then the folder. */
  public static String fallback(String motd, Path folder) {
    String line = plain(motd == null ? "" : motd.lines().findFirst().orElse(""));
    if (!line.isEmpty() && !line.equalsIgnoreCase(VANILLA_MOTD)) {
      return line;
    }
    Path name = folder.toAbsolutePath().normalize().getFileName();
    return name == null ? UNNAMED : bounded(name.toString());
  }

  /** Plain text of at most 64 characters, never empty. */
  public static String bounded(String name) {
    String plain = plain(name == null ? "" : name);
    return plain.isEmpty() ? UNNAMED : plain;
  }

  /** Colour codes, hex colours and MiniMessage tags removed, whitespace collapsed, at most 64 characters. */
  static String plain(String text) {
    String stripped = CODES.matcher(text).replaceAll("").replaceAll("\\s+", " ").strip();
    return stripped.length() > LIMIT ? stripped.substring(0, LIMIT).strip() : stripped;
  }
}
