package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.ParallelMenuAction;

import java.util.ArrayList;
import java.util.List;

public record ParallelActionData(List<List<MenuActionData>> branches, HoloClickTrigger trigger, String when,
                                 Integer cooldownTicks) implements MenuActionData {
  public ParallelActionData {
    List<List<MenuActionData>> copy = new ArrayList<>();
    if (branches != null) {
      for (List<MenuActionData> branch : branches) {
        if (branch != null) {
          copy.add(ControlFlowLists.copy(branch));
        }
      }
    }
    branches = List.copyOf(copy);
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.PARALLEL;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new ParallelMenuAction(this);
  }

  @Override
  public List<MenuActionData> nestedActions() {
    List<MenuActionData> nested = new ArrayList<>();
    for (List<MenuActionData> branch : branches) {
      nested.addAll(branch);
    }
    return List.copyOf(nested);
  }

  @Override
  public String invalidReason() {
    return branches.isEmpty() ? "declares no parallel branches" : null;
  }
}
