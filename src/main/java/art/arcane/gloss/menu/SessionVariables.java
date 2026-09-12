package art.arcane.gloss.menu;

import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprParser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The state one open surface carries: the document's declared {@code vars}, the arguments it was
 * opened with on top, and whatever {@code setSession} has written since.
 *
 * <p>The generation counter is what the session re-renders off. It moves only when a value really
 * changed, so a tab button clicked twice redraws once.
 */
public final class SessionVariables {
    private final ConcurrentMap<String, Object> values = new ConcurrentHashMap<>();
    private final Map<String, Object> args;
    private final AtomicLong generation = new AtomicLong();

    private SessionVariables(Map<String, Object> defaults, Map<String, Object> args) {
        this.args = Map.copyOf(args);
        values.putAll(defaults);
        values.putAll(args);
    }

    public static SessionVariables of(Map<String, Object> defaults, Map<String, Object> args) {
        return new SessionVariables(defaults == null ? Map.of() : defaults, args == null ? Map.of() : args);
    }

    /**
     * Folds a document's {@code vars} block into starting values. A declaration that needs live
     * state is not a default, so it is dropped rather than evaluated against a viewer that does not
     * exist yet.
     */
    public static Map<String, Object> evaluateDeclarations(Map<String, String> declared) {
        if (declared == null || declared.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> defaults = new LinkedHashMap<>(declared.size());
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            Object value = constant(entry.getValue());
            if (value != null) {
                defaults.put(entry.getKey(), value);
            }
        }
        return Map.copyOf(defaults);
    }

    private static Object constant(String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            Expr parsed = ExprParser.parse(expression.trim());
            return ExprEvaluator.isConstant(parsed) ? ExprEvaluator.eval(parsed, null) : null;
        } catch (RuntimeException notConstant) {
            return null;
        }
    }

    public Object get(String name) {
        return name == null ? null : values.get(name);
    }

    /** @return true when the value changed, which is also when the generation moved */
    public boolean set(String name, Object value) {
        Objects.requireNonNull(name, "name");
        Object previous = value == null ? values.remove(name) : values.put(name, value);
        if (Objects.equals(previous, value)) {
            return false;
        }
        generation.incrementAndGet();
        return true;
    }

    public long generation() {
        return generation.get();
    }

    /** The arguments this surface was opened with, which never change for its lifetime. */
    public Map<String, Object> args() {
        return args;
    }
}
