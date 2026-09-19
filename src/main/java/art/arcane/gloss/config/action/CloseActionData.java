package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.CloseMenuAction;
import art.arcane.gloss.menu.action.MenuAction;

/** Closes whichever surface the click came from: a menu session or an inventory window. */
public record CloseActionData(HoloClickTrigger trigger, String when,
                              Integer cooldownTicks) implements MenuActionData {
  @Override
  public MenuActionType getType() {
    return MenuActionType.CLOSE;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new CloseMenuAction(this);
  }
}
