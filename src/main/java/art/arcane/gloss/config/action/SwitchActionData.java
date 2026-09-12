package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.SwitchMenuAction;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SwitchActionData(String on, Map<String, List<MenuActionData>> cases,
                               @SerializedName("default") List<MenuActionData> fallback, HoloClickTrigger trigger,
                               String when, Integer cooldownTicks) implements MenuActionData {
  public SwitchActionData {
    Map<String, List<MenuActionData>> copy = new LinkedHashMap<>();
    if (cases != null) {
      for (Map.Entry<String, List<MenuActionData>> entry : cases.entrySet()) {
        if (entry.getKey() != null && entry.getValue() != null) {
          copy.put(entry.getKey(), ControlFlowLists.copy(entry.getValue()));
        }
      }
    }
    cases = Map.copyOf(copy);
    fallback = ControlFlowLists.copy(fallback);
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.SWITCH;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new SwitchMenuAction(this);
  }

  @Override
  public List<MenuActionData> nestedActions() {
    List<MenuActionData> nested = new ArrayList<>(fallback);
    for (List<MenuActionData> branch : cases.values()) {
      nested.addAll(branch);
    }
    return List.copyOf(nested);
  }

  @Override
  public String invalidReason() {
    String problem = ControlFlowLists.expressionProblem(on, "on");
    if (problem != null) {
      return problem;
    }
    return cases.isEmpty() ? "declares no switch cases" : null;
  }
}
