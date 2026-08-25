package art.arcane.gloss.condition;

import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprScope;

import java.util.Objects;

public final class CompiledCondition {

  private final ConditionSource source;
  private final Expr expression;
  private final ConditionReferences references;

  CompiledCondition(ConditionSource source, Expr expression, ConditionReferences references) {
    this.source = Objects.requireNonNull(source);
    this.expression = Objects.requireNonNull(expression);
    this.references = Objects.requireNonNull(references);
  }

  public ConditionSource source() {
    return source;
  }

  public ConditionReferences references() {
    return references;
  }

  public boolean matches(ExprScope scope) {
    return matches(scope, BoundedConditionErrorCallback.silent());
  }

  public boolean matches(ExprScope scope, BoundedConditionErrorCallback errors) {
    Objects.requireNonNull(scope);
    Objects.requireNonNull(errors);
    try {
      return ExprEvaluator.bool(expression, ConditionScope.wrap(scope));
    } catch (RuntimeException exception) {
      String message = exception.getMessage() == null
          ? exception.getClass().getSimpleName()
          : exception.getMessage();
      errors.report(new ConditionEvaluationError(
          source.path(), source.expression(), message, exception));
      return false;
    }
  }
}
