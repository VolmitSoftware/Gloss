package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.CooldownMenuAction;
import art.arcane.gloss.menu.action.MenuAction;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public record CooldownActionData(String key, Integer ticks, List<MenuActionData> then,
                                 @SerializedName("else") List<MenuActionData> otherwise, HoloClickTrigger trigger,
                                 String when, Integer cooldownTicks) implements MenuActionData {
  public CooldownActionData {
    then = ControlFlowLists.copy(then);
    otherwise = ControlFlowLists.copy(otherwise);
    key = key == null || key.isBlank() ? null : key.trim();
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.COOLDOWN;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new CooldownMenuAction(this);
  }

  @Override
  public List<MenuActionData> nestedActions() {
    return ControlFlowLists.concat(then, otherwise);
  }

  @Override
  public String invalidReason() {
    if (key == null) {
      return "declares no cooldown key";
    }
    return ticks == null || ticks < 1 ? "declares a cooldown without positive ticks" : null;
  }
}
