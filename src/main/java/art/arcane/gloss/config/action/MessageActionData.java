package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;

public record MessageActionData(String message, HoloClickTrigger trigger) implements MenuActionData {
  @Override
  public MenuActionType getType() {
    return MenuActionType.MESSAGE;
  }
}
