package art.arcane.gloss.expr;

/**
 * A dotted variable prefix ({@code state}, {@code args}, {@code input}, ...) resolved at runtime.
 * {@link #resolve} receives the name after the prefix and dot and returns a {@code Double},
 * {@code String}, {@code Boolean}, {@code List}, or {@code null} when the suffix is unknown.
 */
public interface ExprVariableNamespace {
    String prefix();

    Object resolve(String suffix, ExprVariableContext context);
}
