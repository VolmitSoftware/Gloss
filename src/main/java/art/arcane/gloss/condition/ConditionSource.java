package art.arcane.gloss.condition;

import java.util.Objects;

public record ConditionSource(String path, String expression) {

  public ConditionSource {
    path = Objects.requireNonNull(path);
    expression = Objects.requireNonNull(expression);
  }

  public static ConditionSource subjectPermission(String path, String expression, String permission) {
    if (permission == null || permission.isBlank()) {
      return new ConditionSource(path, expression);
    }
    String escaped = permission.trim().replace("\\", "\\\\").replace("'", "\\'");
    return new ConditionSource(path,
        "hasPermission('subject', '" + escaped + "') && (" + expression + ")");
  }
}
