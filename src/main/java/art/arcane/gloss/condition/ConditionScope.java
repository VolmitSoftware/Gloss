package art.arcane.gloss.condition;

import art.arcane.gloss.expr.ExprScope;

import java.util.List;
import java.util.Objects;

public final class ConditionScope implements ExprScope {

  private final ExprScope delegate;

  private ConditionScope(ExprScope delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  public static ConditionScope wrap(ExprScope delegate) {
    if (delegate instanceof ConditionScope conditionScope) {
      return conditionScope;
    }
    return new ConditionScope(delegate);
  }

  @Override
  public Object variable(String dottedName) {
    return delegate.variable(dottedName);
  }

  @Override
  public Object call(String name, List<Object> args) {
    Object conditionResult = ConditionFunctions.call(name, args);
    return conditionResult != null ? conditionResult : delegate.call(name, args);
  }
}
