package art.arcane.gloss.strings;

import art.arcane.gloss.expr.ExprException;
import art.arcane.gloss.expr.ExprFunctionRegistry;
import art.arcane.gloss.expr.ExprScope;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The {@code lang} entry in {@link ExprFunctionRegistry}: every {@code {{ }}}, {@code show} and
 * {@code when} expression can read an operator-authored string in the viewer's own content locale.
 */
public final class LangFunction {
    public static final String NAME = "lang";

    private static final int NO_POSITION = -1;

    private LangFunction() {
    }

    public static ExprFunctionRegistry.Spec spec(LangResolver resolver) {
        return new ExprFunctionRegistry.Spec(NAME, ExprFunctionRegistry.Kind.STRING,
            List.of(ExprFunctionRegistry.Kind.STRING, ExprFunctionRegistry.Kind.ANY), true,
            (scope, args) -> call(resolver, scope, args));
    }

    private static Object call(LangResolver resolver, ExprScope scope, List<Object> args) {
        if (args.isEmpty()) {
            throw new ExprException("lang expects at least 1 argument (the message key), got 0", NO_POSITION);
        }
        if (!(args.get(0) instanceof String key)) {
            throw new ExprException("lang argument 1 (key) must be a string", NO_POSITION);
        }
        Player viewer = scope == null ? null : scope.variableContext().viewer();
        try {
            return resolver.resolve(viewer == null ? null : viewer.getUniqueId(), key, args);
        } catch (IllegalArgumentException invalid) {
            throw new ExprException("lang: " + invalid.getMessage(), NO_POSITION);
        }
    }
}
