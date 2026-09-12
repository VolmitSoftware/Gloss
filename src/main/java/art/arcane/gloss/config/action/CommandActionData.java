package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionCommandSource;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.CommandMenuAction;
import art.arcane.gloss.menu.action.MenuAction;

public record CommandActionData(MenuActionCommandSource source,
                                String command,
                                HoloClickTrigger trigger,
                                String when,
                                Integer cooldownTicks) implements MenuActionData {

  public MenuActionType getType() {
    return MenuActionType.COMMAND;
  }

  public MenuActionCommandSource sourceOrDefault() {
    return source == null ? MenuActionCommandSource.PLAYER : source;
  }

  public boolean hasCommand() {
    if (command == null) {
      return false;
    }
    String trimmed = command.trim();
    return !trimmed.isEmpty() && !trimmed.equals("/");
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new CommandMenuAction(this);
  }

  @Override
  public String invalidReason() {
    return hasCommand() ? null : "declares an empty command";
  }
}
