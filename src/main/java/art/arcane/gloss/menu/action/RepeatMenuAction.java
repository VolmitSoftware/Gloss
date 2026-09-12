package art.arcane.gloss.menu.action;

import art.arcane.gloss.behavior.ActionProgram;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.config.action.RepeatActionData;

import java.util.List;

/**
 * Runs the steps up to {@code times} while the {@code while} condition holds, waiting
 * {@code everyTicks} between iterations; without a wait the loop is synchronous.
 */
public final class RepeatMenuAction extends MenuAction<RepeatActionData> {
  private final List<MenuAction<?>> steps;
  private final CompiledCondition condition;

  public RepeatMenuAction(RepeatActionData data) {
    super(data);
    this.steps = MenuAction.resolve(data.steps(), "repeat", "steps");
    this.condition = data.whileCondition() == null ? null : ConditionCompiler.compile(data.whileCondition());
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    ActionProgram.Frame frame = ActionProgram.current();
    if (frame == null) {
      return ActionOutcome.CONTINUE;
    }
    return iterate(frame, frame.cursor(), 0, false);
  }

  private ActionOutcome iterate(ActionProgram.Frame frame, int at, int iteration, boolean resumed) {
    int index = iteration;
    while (true) {
      if (index >= data.times() || (condition != null && !condition.matches(frame.context().conditionScope()))) {
        if (resumed) {
          frame.resumeAfter(at);
        }
        return ActionOutcome.CONTINUE;
      }
      int next = index + 1;
      ActionProgram.Frame child = frame.child(steps, null, () -> afterIteration(frame, at, next));
      ActionOutcome outcome = child.start();
      if (outcome != ActionOutcome.CONTINUE) {
        return outcome;
      }
      if (data.everyTicksOrDefault() > 0) {
        boolean scheduled = frame.defer(data.everyTicksOrDefault(), () -> iterate(frame, at, next, true));
        return scheduled ? ActionOutcome.SUSPENDED : ActionOutcome.STOP;
      }
      index = next;
    }
  }

  private void afterIteration(ActionProgram.Frame frame, int at, int next) {
    if (data.everyTicksOrDefault() > 0) {
      frame.defer(data.everyTicksOrDefault(), () -> iterate(frame, at, next, true));
      return;
    }
    iterate(frame, at, next, true);
  }

  @Override
  protected boolean requiresPlayer() {
    return false;
  }
}
