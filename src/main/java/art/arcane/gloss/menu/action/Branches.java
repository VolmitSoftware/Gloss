package art.arcane.gloss.menu.action;

import art.arcane.gloss.behavior.ActionProgram;

import java.util.List;

/** Runs a nested branch under the current frame, or as its own program when no frame is active. */
final class Branches {
  private Branches() {
  }

  static ActionOutcome run(List<MenuAction<?>> branch, ActionContext context) {
    if (branch.isEmpty()) {
      return ActionOutcome.CONTINUE;
    }
    ActionProgram.Frame frame = ActionProgram.current();
    return frame == null ? ActionProgram.run(branch, 0, context) : frame.runBranch(branch);
  }
}
