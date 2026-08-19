package art.arcane.gloss.expr;

/**
 * Parse or evaluation error carrying the source character position it occurred at.
 */
public final class ExprException extends RuntimeException {

  private final int position;

  public ExprException(String message, int position) {
    super(message);
    this.position = position;
  }

  public int position() {
    return position;
  }
}
