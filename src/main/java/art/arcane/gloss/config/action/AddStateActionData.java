package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.AddStateMenuAction;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.state.StateSchema;

/** Adds the numeric {@code value} expression to the number state {@code key} of the {@code target} role. */
public record AddStateActionData(String key, String value, String target, HoloClickTrigger trigger,
                                 String when, Integer cooldownTicks) implements MenuActionData {
  public AddStateActionData {
    key = key == null || key.isBlank() ? null : key.trim();
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.ADD_STATE;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new AddStateMenuAction(this);
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
