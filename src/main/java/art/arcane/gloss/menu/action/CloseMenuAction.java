package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.CloseActionData;

/** Closes the surface the click came from; nothing after it in the list would have anywhere to run. */
public final class CloseMenuAction extends MenuAction<CloseActionData> {

  public CloseMenuAction(CloseActionData data) {
    super(data);
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    context.closeSurface(context.player());
    return ActionOutcome.STOP;
  }
}
