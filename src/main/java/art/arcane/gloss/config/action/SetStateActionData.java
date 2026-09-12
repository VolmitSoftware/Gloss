package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.SetStateMenuAction;
import art.arcane.gloss.state.StateSchema;

/** Writes {@code value} (an expression) to {@code key} for the {@code target} role (default viewer). */
public record SetStateActionData(String key, String value, String target, HoloClickTrigger trigger,
                                 String when, Integer cooldownTicks) implements MenuActionData {
  public SetStateActionData {
    key = key == null || key.isBlank() ? null : key.trim();
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.SET_STATE;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new SetStateMenuAction(this);
  }

  @Override
  public String invalidReason() {
    if (key == null || !StateSchema.KEY.matcher(key).matches()) {
      return "declares no valid state key";
    }
    String problem = ControlFlowLists.expressionProblem(value, "value");
    return problem != null ? problem : ControlFlowLists.roleProblem(target, "target");
  }
}
