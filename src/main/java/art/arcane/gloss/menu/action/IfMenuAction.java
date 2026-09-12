package art.arcane.gloss.menu.action;

import art.arcane.gloss.behavior.ActionProgram;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.config.action.IfActionData;

import java.util.List;

public final class IfMenuAction extends MenuAction<IfActionData> {
  private final CompiledCondition condition;
  private final List<MenuAction<?>> then;
  private final List<MenuAction<?>> otherwise;

  public IfMenuAction(IfActionData data) {
    super(data);
    this.condition = ConditionCompiler.compile(data.when());
    this.then = MenuAction.resolve(data.then(), "if", "then");
    this.otherwise = MenuAction.resolve(data.otherwise(), "if", "else");
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    List<MenuAction<?>> branch = condition.matches(context.conditionScope()) ? then : otherwise;
    return Branches.run(branch, context);
  }

  @Override
  protected boolean requiresPlayer() {
    return false;
  }
}
