package art.arcane.gloss.editor;

import art.arcane.volmlib.util.editor.EditorTokens;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** What the editor tells the console and, in chat, the pairing player and holders of {@code gloss.editor.notify}. */
final class EditorNotices {
  private final EditorHost host;

  EditorNotices(EditorHost host) {
    this.host = Objects.requireNonNull(host);
  }

  /**
   * {@code text} with control characters, line separators and bidirectional overrides as {@code ?}, so it stays one
   * log line that reads as written.
   */
  static String printable(String text) {
    StringBuilder printable = new StringBuilder(text.length());
    text.codePoints().forEach((int point) -> {
      int type = Character.getType(point);
      boolean hidden = type == Character.CONTROL || type == Character.LINE_SEPARATOR
          || type == Character.PARAGRAPH_SEPARATOR || point >= 0x202A && point <= 0x202E
          || point >= 0x2066 && point <= 0x2069;
      printable.appendCodePoint(hidden ? '?' : point);
    });
    return printable.toString();
  }

  /** {@code tok_x (label)}, the way the console names a token. */
  static String name(String tokenId, String label) {
    return label == null || label.isBlank() ? tokenId : tokenId + " (" + label + ")";
  }

  private String who(Optional<UUID> admin) {
    return admin.map(host::playerName).orElse("Console");
  }

  void paired(EditorTokens.Token<EditorScope> token) {
    String line = who(token.admin()) + "'s editor paired as " + name(token.id(), token.label())
        + token.client().map((String client) -> " from " + client).orElse("");
    host.log("Editor " + line);
    host.notice(token.admin(), line);
  }

  void published(EditorTokens.Token<EditorScope> token, String message) {
    host.log("Web editor publish by " + name(token.id(), token.label()) + ": " + message);
    host.notice(Optional.empty(), who(token.admin()) + "'s editor: " + message);
  }

  void revoked(String tokenId, String label, Optional<UUID> admin, String cause) {
    host.notice(admin, "Editor token " + name(tokenId, label) + " was revoked " + cause);
  }

  /** Tells the player who made a link, in game, that a browser could not use it. */
  void refused(EditorTokens.Open<EditorScope> pairing, String why) {
    host.notice(pairing.grant().admin(), who(pairing.grant().admin()) + "'s editor could not pair: " + why);
  }
}
