package art.arcane.gloss.expr;

import java.util.List;
import java.util.Map;

/**
 * Minimal {@link ExprScope} for tests: variables come from a fixed map, calls fall straight
 * through to {@link ExprFunctions} (there are no context-specific names at this layer).
 */
final class MapScope implements ExprScope {

  private final Map<String, Object> variables;

  MapScope(Map<String, Object> variables) {
    this.variables = variables;
  }

  static MapScope empty() {
    return new MapScope(Map.of());
  }

  @Override
  public Object variable(String dottedName) {
    return variables.get(dottedName);
  }

  @Override
  public Object call(String name, List<Object> args) {
    return ExprFunctions.call(name, args);
  }
}
