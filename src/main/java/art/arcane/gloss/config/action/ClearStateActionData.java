package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.ClearStateMenuAction;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.state.StateSchema;

public record ClearStateActionData(String key, String target, HoloClickTrigger trigger, String when,
                                   Integer cooldownTicks) implements MenuActionData {
  public ClearStateActionData {
    key = key == null || key.isBlank() ? null : key.trim();
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.CLEAR_STATE;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new ClearStateMenuAction(this);
  }

  @Override
  public String invalidReason() {
    if (key == null || !StateSchema.KEY.matcher(key).matches()) {
      return "declares no valid state key";
    }
    return ControlFlowLists.roleProblem(target, "target");
  }
}
