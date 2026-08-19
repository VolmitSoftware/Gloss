package art.arcane.gloss.preview.doc;

/**
 * Compile-time failure for a preview JSON document: malformed JSON, a structurally invalid field,
 * or an expression that fails to parse, fold, or reference a known variable. {@link #getMessage()}
 * is always {@code documentName + " " + message}, so callers can log it directly; {@code message}
 * itself should read {@code "<field path>: <detail>"} (e.g.
 * {@code "elements[3].color: unexpected token at 12"}) for every failure that traces to one field.
 */
public final class PreviewDocumentException extends RuntimeException {

  private final String documentName;

  public PreviewDocumentException(String documentName, String message, Throwable cause) {
    super(documentName + " " + message, cause);
    this.documentName = documentName;
  }

  public String documentName() {
    return documentName;
  }
}
