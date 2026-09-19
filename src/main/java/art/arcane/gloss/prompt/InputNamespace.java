package art.arcane.gloss.prompt;

import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.expr.ExprVariableNamespace;

import java.util.Map;

public final class InputNamespace implements ExprVariableNamespace {
    public static final String PREFIX = "input";

    private static final ThreadLocal<Map<String, Object>> ACTIVE = new ThreadLocal<>();

    public static void bind(Map<String, Object> inputs, Runnable body) {
        Map<String, Object> previous = ACTIVE.get();
        ACTIVE.set(inputs == null ? Map.of() : Map.copyOf(inputs));
        try {
            body.run();
        } finally {
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public Object resolve(String suffix, ExprVariableContext context) {
        Map<String, Object> inputs = ACTIVE.get();
        return inputs == null ? null : inputs.get(suffix);
    }
}
