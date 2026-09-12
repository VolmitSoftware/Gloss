package art.arcane.gloss.state;

import art.arcane.gloss.expr.ExprException;
import art.arcane.gloss.expr.ExprFunctionRegistry;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprVariableContext;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;

/**
 * {@code stateOf(role, key)}: the state value of the viewer, subject or source instead of the
 * viewer the {@code state} namespace assumes. A role the scope cannot supply reads the key's
 * default, so a kill condition without a source still evaluates.
 */
public final class StateOfFunction {
    public static final String NAME = "stateOf";

    private StateOfFunction() {
    }

    public static ExprFunctionRegistry.Spec spec(StateStore store) {
        Objects.requireNonNull(store, "store");
        return new ExprFunctionRegistry.Spec(NAME, ExprFunctionRegistry.Kind.ANY,
            List.of(ExprFunctionRegistry.Kind.STRING, ExprFunctionRegistry.Kind.STRING), false,
            (scope, args) -> call(store, scope, args));
    }

    private static Object call(StateStore store, ExprScope scope, List<Object> args) {
        String role = string(args, 0, "role");
        String key = string(args, 1, "key");
        StateSchema schema = store.declarations().get(key);
        if (schema == null) {
            throw new ExprException(NAME + " does not know the state key " + key, -1);
        }
        Entity entity = roleEntity(scope.variableContext(), role);
        return switch (schema.scope()) {
            case GLOBAL -> store.get(StateScope.GLOBAL, null, key);
            case PLAYER -> entity instanceof Player player
                ? store.get(StateScope.PLAYER, player.getUniqueId(), key) : schema.defaultValue();
            case WORLD -> entity == null ? schema.defaultValue()
                : store.get(StateScope.WORLD, entity.getWorld().getUID(), key);
        };
    }

    private static Entity roleEntity(ExprVariableContext context, String role) {
        return switch (role) {
            case "viewer" -> context.viewer();
            case "subject" -> context.subject();
            case "source" -> context.source();
            default -> throw new ExprException(NAME + " role must be viewer, subject, or source: " + role, -1);
        };
    }

    private static String string(List<Object> args, int index, String what) {
        if (args.size() <= index || !(args.get(index) instanceof String value)) {
            throw new ExprException(NAME + " " + what + " must be a string", -1);
        }
        return value;
    }
}
