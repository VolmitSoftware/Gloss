package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.SetSessionMenuAction;

import com.google.gson.annotations.SerializedName;

/** Writes one session variable; the components that read it re-render on the next pass. */
public record SetSessionActionData(@SerializedName("var") String variable, String value,
                                   HoloClickTrigger trigger, String when,
                                   Integer cooldownTicks) implements MenuActionData {
  @Override
  public MenuActionType getType() {
    return MenuActionType.SET_SESSION;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new SetSessionMenuAction(this);
  }

  @Override
  public String invalidReason() {
    if (variable == null || variable.isBlank()) {
      return "declares a setSession action without a var";
    }
    return value == null || value.isBlank() ? "declares a setSession action without a value" : null;
  }
}
