package art.arcane.gloss.exceptions;

public abstract class GlossMenuException extends Exception {

  private final ComponentType type;

  public GlossMenuException(ComponentType type, String message) {
    super(message);
    this.type = type;
  }

  public GlossMenuException(ComponentType type, String format, Object... objects) {
    super(String.format(format, objects));
    this.type = type;
  }

  public ComponentType getType() {
    return type;
  }

  public enum ComponentType {
    ICON,
    COMPONENT,
    ACTION
  }
}
