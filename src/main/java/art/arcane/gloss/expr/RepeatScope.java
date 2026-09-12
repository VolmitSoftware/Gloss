package art.arcane.gloss.expr;

import java.util.List;

/**
 * One loop variable in front of a parent scope. A repeated preview element, a menu list entry and a
 * chest list entry all bind exactly one name this way, so the binding lives here rather than three
 * times over.
 */
public record RepeatScope(ExprScope parent, String name, Object value) implements ExprScope {

  @Override
  public Object variable(String dottedName) {
    return name.equals(dottedName) ? value : parent.variable(dottedName);
  }

  @Override
  public Object call(String function, List<Object> args) {
    return parent.call(function, args);
  }
}
