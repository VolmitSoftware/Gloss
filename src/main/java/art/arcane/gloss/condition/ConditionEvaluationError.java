package art.arcane.gloss.condition;

import java.util.Objects;

public record ConditionEvaluationError(String path, String source, String message, RuntimeException cause) {

  public ConditionEvaluationError {
    path = Objects.requireNonNull(path);
    source = Objects.requireNonNull(source);
    message = Objects.requireNonNull(message);
    cause = Objects.requireNonNull(cause);
  }
}
