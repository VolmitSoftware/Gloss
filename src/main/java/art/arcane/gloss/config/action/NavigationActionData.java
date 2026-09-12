package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.enums.NavigationMode;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.NavigateMenuAction;

public record NavigationActionData(String target, NavigationMode mode, String list,
                                   HoloClickTrigger trigger,
                                   String when, Integer cooldownTicks) implements MenuActionData {
  @Override
  public MenuActionType getType() {
    return MenuActionType.NAVIGATE;
  }

  public NavigationMode modeOrDefault() {
    return mode == null ? NavigationMode.PUSH : mode;
  }

  public boolean isValid() {
    return switch (modeOrDefault()) {
      case PUSH, REPLACE, PAGE -> target != null && !target.isBlank();
      case BACK, HOME, CLOSE -> true;
    };
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new NavigateMenuAction(this);
  }

  @Override
  public String invalidReason() {
    return isValid() ? null : "declares navigation without a target";
  }
}
