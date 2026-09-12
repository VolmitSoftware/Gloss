package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.RepeatMenuAction;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public record RepeatActionData(Integer times, Integer everyTicks, List<MenuActionData> steps,
                               @SerializedName("while") String whileCondition, HoloClickTrigger trigger,
                               String when, Integer cooldownTicks) implements MenuActionData {
  public static final int MAX_TIMES = 100_000;

  public RepeatActionData {
    steps = ControlFlowLists.copy(steps);
    whileCondition = whileCondition == null || whileCondition.isBlank() ? null : whileCondition.trim();
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.REPEAT;
  }

  public int everyTicksOrDefault() {
    return everyTicks == null ? 0 : everyTicks;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new RepeatMenuAction(this);
  }

  @Override
  public List<MenuActionData> nestedActions() {
    return steps;
  }

  @Override
  public String invalidReason() {
    if (times == null || times < 1 || times > MAX_TIMES) {
      return "declares repeat times outside 1.." + MAX_TIMES;
    }
    if (everyTicks != null && (everyTicks < 0 || everyTicks > DelayActionData.MAX_TICKS)) {
      return "declares everyTicks outside 0.." + DelayActionData.MAX_TICKS;
    }
    if (everyTicksOrDefault() == 0 && times > 1024) {
      return "declares more than 1024 synchronous repetitions; add everyTicks";
    }
    return whileCondition == null ? null : ControlFlowLists.conditionProblem(whileCondition, "while");
  }
}
