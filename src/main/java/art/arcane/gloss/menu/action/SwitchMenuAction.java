package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.config.action.SwitchActionData;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Evaluates {@code on}, stringifies it the way {@code {{ }}} would, and runs the matching case or the default. */
public final class SwitchMenuAction extends MenuAction<SwitchActionData> {
  private final Expr on;
  private final Map<String, List<MenuAction<?>>> cases;
  private final List<MenuAction<?>> fallback;

  public SwitchMenuAction(SwitchActionData data) {
    super(data);
    this.on = ExprParser.parse(data.on());
    Map<String, List<MenuAction<?>>> resolved = new LinkedHashMap<>();
    for (Map.Entry<String, List<MenuActionData>> entry : data.cases().entrySet()) {
      resolved.put(entry.getKey(), MenuAction.resolve(entry.getValue(), "switch", "case:" + entry.getKey()));
    }
    this.cases = Map.copyOf(resolved);
    this.fallback = MenuAction.resolve(data.fallback(), "switch", "default");
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    String value = ExprEvaluator.string(on, context.conditionScope());
    List<MenuAction<?>> branch = cases.get(value);
    return Branches.run(branch == null ? fallback : branch, context);
  }

  @Override
  protected boolean requiresPlayer() {
    return false;
  }
}
