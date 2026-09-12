package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.IfMenuAction;
import art.arcane.gloss.menu.action.MenuAction;
import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * {@code when} is this action's own condition (it picks {@code then} or {@code else}), not an
 * envelope gate: a false condition must still run the {@code else} list.
 */
public record IfActionData(String when, List<MenuActionData> then, @SerializedName("else") List<MenuActionData> otherwise,
                           HoloClickTrigger trigger, Integer cooldownTicks) implements MenuActionData {
  public IfActionData {
    then = ControlFlowLists.copy(then);
    otherwise = ControlFlowLists.copy(otherwise);
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.IF;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(null, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new IfMenuAction(this);
  }

  @Override
  public List<MenuActionData> nestedActions() {
    return ControlFlowLists.concat(then, otherwise);
  }

  @Override
  public String invalidReason() {
    return ControlFlowLists.conditionProblem(when, "when");
  }
}
