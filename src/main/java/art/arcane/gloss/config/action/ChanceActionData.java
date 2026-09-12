package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.ChanceMenuAction;
import art.arcane.gloss.menu.action.MenuAction;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public record ChanceActionData(Double percent, List<MenuActionData> then, @SerializedName("else") List<MenuActionData> otherwise,
                               HoloClickTrigger trigger, String when, Integer cooldownTicks) implements MenuActionData {
  public ChanceActionData {
    then = ControlFlowLists.copy(then);
    otherwise = ControlFlowLists.copy(otherwise);
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.CHANCE;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new ChanceMenuAction(this);
  }

  @Override
  public List<MenuActionData> nestedActions() {
    return ControlFlowLists.concat(then, otherwise);
  }

  @Override
  public String invalidReason() {
    return percent == null || !Double.isFinite(percent) || percent < 0.0D || percent > 100.0D
        ? "declares a chance percent outside 0..100" : null;
  }
}
