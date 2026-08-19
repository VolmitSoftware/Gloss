package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.NavigationActionData;

public final class NavigateMenuAction extends MenuAction<NavigationActionData> {
  public NavigateMenuAction(NavigationActionData data) {
    super(data);
  }

  public boolean isValid() {
    return switch (data.modeOrDefault()) {
      case PUSH, REPLACE -> data.target() != null && !data.target().isBlank();
      case BACK, HOME, CLOSE -> true;
    };
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
