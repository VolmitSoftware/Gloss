package art.arcane.gloss.dialog;

import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.expr.ExprVariableNamespace;

import java.util.Map;

/**
 * The {@code input.<key>} namespace. A dialog's answers only exist while its button's actions are
 * running, so the binding lives on the thread that runs them and is torn down when they finish; a
 * later expression asking for {@code input.qty} gets nothing rather than a stale answer from
 * whichever dialog was open last.
 */
public final class InputNamespace implements ExprVariableNamespace {
    public static final String PREFIX = "input";

    private static final ThreadLocal<Binding> ACTIVE = new ThreadLocal<>();

    /** What one dialog response bound: the document that asked, and the answers it got. */
    public record Binding(String dialogId, Map<String, Object> inputs) {
    }

    /** Runs {@code body} with these answers visible to {@code input.*} and {@code dialog.id}. */
    public static void bind(String dialogId, Map<String, Object> inputs, Runnable body) {
        Binding previous = ACTIVE.get();
        ACTIVE.set(new Binding(dialogId, inputs == null ? Map.of() : Map.copyOf(inputs)));
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

    public static Binding active() {
        return ACTIVE.get();
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public Object resolve(String suffix, ExprVariableContext context) {
        Binding binding = ACTIVE.get();
        return binding == null ? null : binding.inputs().get(suffix);
    }
}
