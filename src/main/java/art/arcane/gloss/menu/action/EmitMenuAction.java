package art.arcane.gloss.menu.action;

import art.arcane.gloss.behavior.EmitBus;
import art.arcane.gloss.config.action.EmitActionData;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprException;
import art.arcane.gloss.expr.ExprParser;

import java.util.LinkedHashMap;
import java.util.Map;

public final class EmitMenuAction extends MenuAction<EmitActionData> {
  private final Map<String, Object> literal;
  private final Map<String, Expr> evaluated;

  public EmitMenuAction(EmitActionData data) {
    super(data);
    Map<String, Object> literals = new LinkedHashMap<>();
    Map<String, Expr> expressions = new LinkedHashMap<>();
    for (Map.Entry<String, Object> arg : data.args().entrySet()) {
      Expr expression = arg.getValue() instanceof String text ? wrapped(text) : null;
      if (expression != null) {
        expressions.put(arg.getKey(), expression);
      } else {
        literals.put(arg.getKey(), arg.getValue());
      }
    }
    this.literal = Map.copyOf(literals);
    this.evaluated = Map.copyOf(expressions);
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    Map<String, Object> args = new LinkedHashMap<>(literal);
    for (Map.Entry<String, Expr> arg : evaluated.entrySet()) {
      args.put(arg.getKey(), ExprEvaluator.eval(arg.getValue(), context.conditionScope()));
    }
    EmitBus.emit(data.name(), args, context.player(), context.menuId());
    return ActionOutcome.CONTINUE;
  }

  private static Expr wrapped(String text) {
    String trimmed = text.trim();
    if (!trimmed.startsWith("{{") || !trimmed.endsWith("}}")) {
      return null;
    }
    try {
      return ExprParser.parse(trimmed.substring(2, trimmed.length() - 2).trim());
    } catch (ExprException invalid) {
      return null;
    }
  }

  @Override
  protected boolean requiresPlayer() {
    return false;
  }
}
