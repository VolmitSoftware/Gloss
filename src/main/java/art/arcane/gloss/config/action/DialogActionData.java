package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.DialogMenuAction;
import art.arcane.gloss.menu.action.MenuAction;

import java.util.Map;

/** Opens a {@code dialogs/} document, seeding {@code args.*} for the screen it draws. */
public record DialogActionData(String id, Map<String, Object> args, HoloClickTrigger trigger,
                               String when, Integer cooldownTicks) implements MenuActionData {
  public DialogActionData {
    args = args == null ? Map.of() : Map.copyOf(args);
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.DIALOG;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new DialogMenuAction(this);
  }

  @Override
  public String invalidReason() {
    return id == null || id.isBlank() ? "declares a dialog action without an id" : null;
  }
}
