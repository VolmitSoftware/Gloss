package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.GiveMenuAction;
import art.arcane.gloss.menu.action.MenuAction;

/** Gives the clicking player items. Continues the list unless a full inventory had nowhere to go. */
public record GiveActionData(MenuIconData item, String amount, Boolean dropIfFull, HoloClickTrigger trigger,
                             String when, Integer cooldownTicks) implements MenuActionData {
  public GiveActionData {
    amount = amount == null || amount.isBlank() ? "1" : amount.trim();
  }

  public boolean dropIfFullOrDefault() {
    return dropIfFull == null || dropIfFull;
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.GIVE;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new GiveMenuAction(this);
  }

  @Override
  public String invalidReason() {
    return item == null ? "declares a give action with no item" : null;
  }
}
