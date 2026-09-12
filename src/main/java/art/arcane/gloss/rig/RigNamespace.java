package art.arcane.gloss.rig;

import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.expr.ExprVariableNamespace;

import java.util.function.Supplier;

public final class RigNamespace implements ExprVariableNamespace {
    public static final String PREFIX = "rig";
    private static final String VAR_PREFIX = "var.";
    private static final ThreadLocal<RigScope> CURRENT = new ThreadLocal<>();

    public static <T> T with(RigScope scope, Supplier<T> action) {
        RigScope previous = CURRENT.get();
        CURRENT.set(scope);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static RigScope current() {
        return CURRENT.get();
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public Object resolve(String suffix, ExprVariableContext context) {
        return resolveFor(CURRENT.get(), suffix);
    }

    public static Object resolveFor(RigScope scope, String suffix) {
        if (scope == null || suffix == null) {
            return null;
        }
        if (suffix.equals("state")) {
            return scope.state();
        }
        if (suffix.equals("instance")) {
            return scope.instanceId();
        }
        if (suffix.startsWith(VAR_PREFIX) && suffix.length() > VAR_PREFIX.length()) {
            return scope.var(suffix.substring(VAR_PREFIX.length()));
        }
        return null;
    }
}
