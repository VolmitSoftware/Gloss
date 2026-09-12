package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.EmitMenuAction;
import art.arcane.gloss.menu.action.MenuAction;

import java.util.Map;

/** Fires the {@code emit} trigger {@code name}; string args wrapped in {@code {{ }}} are evaluated, other values pass as written. */
public record EmitActionData(String name, Map<String, Object> args, HoloClickTrigger trigger, String when,
                             Integer cooldownTicks) implements MenuActionData {
  public EmitActionData {
    args = args == null ? Map.of() : Map.copyOf(args);
    name = name == null || name.isBlank() ? null : name.trim();
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.EMIT;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new EmitMenuAction(this);
  }

  @Override
  public String invalidReason() {
    return name == null ? "declares no emit name" : null;
  }
}
