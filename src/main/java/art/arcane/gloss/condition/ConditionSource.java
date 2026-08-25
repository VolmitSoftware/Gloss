package art.arcane.gloss.condition;

import java.util.Objects;

public record ConditionSource(String path, String expression) {

  public ConditionSource {
    path = Objects.requireNonNull(path);
    expression = Objects.requireNonNull(expression);
  }
}
