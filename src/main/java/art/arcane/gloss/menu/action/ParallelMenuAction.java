package art.arcane.gloss.menu.action;

import art.arcane.gloss.behavior.ActionProgram;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.config.action.ParallelActionData;

import java.util.ArrayList;
import java.util.List;

/** Each branch is its own program on the same continuation; the list continues at once. */
public final class ParallelMenuAction extends MenuAction<ParallelActionData> {
  private final List<List<MenuAction<?>>> branches;

  public ParallelMenuAction(ParallelActionData data) {
    super(data);
    List<List<MenuAction<?>>> resolved = new ArrayList<>(data.branches().size());
    int index = 0;
    for (List<MenuActionData> branch : data.branches()) {
      resolved.add(MenuAction.resolve(branch, "parallel", "branch:" + index++));
    }
    this.branches = List.copyOf(resolved);
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    ActionProgram.Frame frame = ActionProgram.current();
    for (List<MenuAction<?>> branch : branches) {
      if (frame == null) {
        ActionProgram.run(branch, 0, context);
      } else {
        ActionProgram.run(branch, 0, context, frame.continuation());
      }
    }
    return ActionOutcome.CONTINUE;
  }

  @Override
  protected boolean requiresPlayer() {
    return false;
  }
}
