package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.SetSessionActionData;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.menu.SessionVariables;

/**
 * Writes one session variable and keeps the list running: setting a tab and then messaging about it
 * is one click, not two.
 */
public final class SetSessionMenuAction extends MenuAction<SetSessionActionData> {

  public SetSessionMenuAction(SetSessionActionData data) {
    super(data);
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    SessionVariables variables = context.sessionVariables();
    if (variables == null) {
      return ActionOutcome.CONTINUE;
    }
    try {
      variables.set(data.variable(), ExprEvaluator.eval(ExprParser.parse(data.value()), context.conditionScope()));
    } catch (RuntimeException unresolvable) {
      return ActionOutcome.CONTINUE;
    }
    return ActionOutcome.CONTINUE;
  }
}
