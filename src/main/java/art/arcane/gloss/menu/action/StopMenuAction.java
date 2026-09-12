package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.StopActionData;

public final class StopMenuAction extends MenuAction<StopActionData> {
  public StopMenuAction(StopActionData data) {
    super(data);
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    return ActionOutcome.STOP;
  }

  @Override
  protected boolean requiresPlayer() {
    return false;
  }
}
