package art.arcane.gloss.editor;

/** The editor's permission nodes. */
public final class EditorPermissions {
  /** {@code /gloss editor pair|status|tokens|revoke}; a player's tokens end when they lose it. */
  public static final String PAIR = "gloss.editor.pair";
  /** Pairing {@code publish:commands} in game; the console always may. */
  public static final String COMMANDS = "gloss.editor.commands";
  /** Chat notices of pairings, publishes, revocations and failed pairings. */
  public static final String NOTIFY = "gloss.editor.notify";

  private EditorPermissions() {
  }
}
