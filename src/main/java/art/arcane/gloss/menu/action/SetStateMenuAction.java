package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.action.SetStateActionData;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.state.StateSchema;
import art.arcane.gloss.state.StateStore;

import java.util.UUID;

public final class SetStateMenuAction extends MenuAction<SetStateActionData> {
  private final Expr value;

  public SetStateMenuAction(SetStateActionData data) {
    super(data);
    this.value = ExprParser.parse(data.value());
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    StateStore store = StateWrites.store(data.key(), context);
    if (store == null) {
      return ActionOutcome.CONTINUE;
    }
    StateSchema schema = store.declarations().get(data.key());
    UUID owner = StateWrites.owner(store, data.key(), data.target(), context);
    if (StateWrites.missingOwner(schema, owner)) {
      return ActionOutcome.CONTINUE;
    }
    try {
      store.set(schema.scope(), owner, data.key(), ExprEvaluator.eval(value, context.conditionScope()));
    } catch (RuntimeException failure) {
      Gloss.logExceptionStackThrottled(false, "state-set:" + data.key(), failure,
          "%s could not set state \"%s\".", context.menuId(), data.key());
    }
    return ActionOutcome.CONTINUE;
  }

  @Override
  protected boolean requiresPlayer() {
    return false;
  }
}
