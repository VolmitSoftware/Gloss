package art.arcane.gloss.menu.action;

import art.arcane.gloss.behavior.ActionProgram;
import art.arcane.gloss.config.action.DelayActionData;

public final class DelayMenuAction extends MenuAction<DelayActionData> {
  public DelayMenuAction(DelayActionData data) {
    super(data);
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    ActionProgram.Frame frame = ActionProgram.current();
    if (frame == null) {
      return ActionOutcome.CONTINUE;
    }
    return frame.suspendAfterCurrent(data.ticks()) ? ActionOutcome.SUSPENDED : ActionOutcome.STOP;
  }

  @Override
  protected boolean requiresPlayer() {
    return false;
  }
}
