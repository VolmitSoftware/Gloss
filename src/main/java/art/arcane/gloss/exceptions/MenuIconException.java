package art.arcane.gloss.exceptions;

public class MenuIconException extends GlossMenuException {
  public MenuIconException(String message) {
    super(ComponentType.ICON, message);
  }

  public MenuIconException(String format, Object... objects) {
    super(ComponentType.ICON, format, objects);
  }
}
