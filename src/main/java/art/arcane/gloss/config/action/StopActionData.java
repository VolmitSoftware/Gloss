package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.StopMenuAction;

public record StopActionData(HoloClickTrigger trigger, String when, Integer cooldownTicks) implements MenuActionData {
  @Override
  public MenuActionType getType() {
    return MenuActionType.STOP;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new StopMenuAction(this);
  }
}
