package art.arcane.gloss.condition;

import java.util.Objects;

public final class ConditionValidationException extends IllegalArgumentException {

  private final String path;
  private final String source;
  private final int position;

  ConditionValidationException(String path, String source, String detail, int position, Throwable cause) {
    super(formatMessage(path, detail, position), cause);
    this.path = Objects.requireNonNull(path);
    this.source = Objects.requireNonNull(source);
    this.position = position;
  }

  public String path() {
    return path;
  }

  public String source() {
    return source;
  }

  public int position() {
    return position;
  }

  private static String formatMessage(String path, String detail, int position) {
    String location = position >= 0 ? path + " at character " + position : path;
    return location + ": " + detail;
  }
}
