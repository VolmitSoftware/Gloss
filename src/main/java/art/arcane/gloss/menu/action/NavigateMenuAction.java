package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.NavigationActionData;

public final class NavigateMenuAction extends MenuAction<NavigationActionData> {
  public NavigateMenuAction(NavigationActionData data) {
    super(data);
  }

  public boolean isValid() {
    return data.isValid();
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    if (!isValid()) {
      return ActionOutcome.STOP;
    }
    context.navigate(new NavigationRequest(data.modeOrDefault(), data.target()));
    return ActionOutcome.STOP;
  }
}
