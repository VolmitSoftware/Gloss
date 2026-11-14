package art.arcane.gloss.editor.sync;

/**
 * The workspace is bigger than {@code [editor] maxProjectMiB} lets the server hold. {@code maximumBytes} may be the
 * in-process ceiling derived from that setting, so the message names the key and not that number.
 */
public final class EditorSyncProjectTooLargeException extends IllegalArgumentException {
  private final long actualBytes;
  private final long maximumBytes;

  public EditorSyncProjectTooLargeException(long actualBytes, long maximumBytes) {
    this(actualBytes, maximumBytes,
        "the workspace is larger than [editor] maxProjectMiB allows; raise maxProjectMiB in gloss.toml");
  }

  EditorSyncProjectTooLargeException(long actualBytes, long maximumBytes, String message) {
    super(message);
    this.actualBytes = actualBytes;
    this.maximumBytes = maximumBytes;
  }

  public long actualBytes() {
    return actualBytes;
  }

  public long maximumBytes() {
    return maximumBytes;
  }
}
