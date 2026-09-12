package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.MessageMenuAction;

public record MessageActionData(String message, HoloClickTrigger trigger,
                                String when, Integer cooldownTicks) implements MenuActionData {
  @Override
  public MenuActionType getType() {
    return MenuActionType.MESSAGE;
  }

  public boolean hasMessage() {
    return message != null && !message.isBlank();
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new MessageMenuAction(this);
  }

  @Override
  public String invalidReason() {
    return hasMessage() ? null : "declares an empty message";
  }
}
