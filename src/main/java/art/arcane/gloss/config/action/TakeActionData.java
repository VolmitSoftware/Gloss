package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.TakeMenuAction;

/** Takes items from the clicking player. Stops the list when they do not have them. */
public record TakeActionData(MenuIconData item, String amount, String denyMessage, HoloClickTrigger trigger,
                             String when, Integer cooldownTicks) implements MenuActionData {
  public TakeActionData {
    amount = amount == null || amount.isBlank() ? "1" : amount.trim();
    denyMessage = denyMessage == null || denyMessage.isBlank() ? null : denyMessage;
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.TAKE;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new TakeMenuAction(this);
  }

  @Override
  public String invalidReason() {
    return item == null ? "declares a take action with no item" : null;
  }
}
