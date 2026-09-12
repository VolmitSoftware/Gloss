package art.arcane.gloss.leaderboard;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprFunctionRegistry;
import art.arcane.gloss.expr.ExprScope;

import java.util.List;

/**
 * The two bindings a leaderboard's {@code format} templates may read. Rows are formatted once per
 * recompute, so this scope deliberately knows nothing about a viewer.
 */
public final class LeaderboardRowScope implements ExprScope {
    private final Gloss plugin;
    private final String name;
    private final double value;

    public LeaderboardRowScope(Gloss plugin, String name, double value) {
        this.plugin = plugin;
        this.name = name;
        this.value = value;
    }

    @Override
    public Object variable(String dottedName) {
        return switch (dottedName) {
            case "name" -> name;
            case "value" -> value;
            default -> plugin.text().expressionScope(null).variable(dottedName);
        };
    }

    @Override
    public Object call(String name, List<Object> args) {
        Object value = ExprFunctions.call(name, args);
        return value != null ? value : ExprFunctionRegistry.global().call(this, name, args);
    }
}
