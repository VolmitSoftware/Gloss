package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionCommandSource;
import art.arcane.gloss.enums.MenuActionType;

public record CommandActionData(MenuActionCommandSource source,
                                String command,
                                HoloClickTrigger trigger) implements MenuActionData {

  public MenuActionType getType() {
    return MenuActionType.COMMAND;
  }

  public MenuActionCommandSource sourceOrDefault() {
    return source == null ? MenuActionCommandSource.PLAYER : source;
  }
}
