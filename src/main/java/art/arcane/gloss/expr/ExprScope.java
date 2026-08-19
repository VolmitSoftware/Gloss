package art.arcane.gloss.expr;

import java.util.List;

/**
 * Variable and function resolution for the preview expression language. Implementations bridge
 * live document/Bukkit state (variables, adapter snapshots, provider namespaces) and layered
 * function namespaces (context-specific names falling back to {@link ExprFunctions}) into the
 * tree-walking {@link ExprEvaluator}.
 */
public interface ExprScope {

  /**
   * Resolves a possibly-dotted variable name (e.g. {@code "inventory.size"}).
   *
   * @return a {@code Double}, {@code String}, {@code Boolean}, or {@code List<Object>}, or
   *     {@code null} if the name is not known to this scope. The evaluator raises an
   *     {@link ExprException} when this returns {@code null} for a referenced variable.
   */
  Object variable(String dottedName);

  /**
   * Invokes a function call by name with already-evaluated arguments.
   *
   * @return the call result ({@code Double}, {@code String}, or {@code Boolean}), or
   *     {@code null} if the name is unknown to this scope. The evaluator raises an
   *     {@link ExprException} when this returns {@code null} for a referenced call. Scope
   *     implementations should resolve their own context-specific names first, then fall back
   *     to {@link ExprFunctions#call(String, List)} for the standard library.
   */
  Object call(String name, List<Object> args);
}
