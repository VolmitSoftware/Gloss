package art.arcane.gloss.chat;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.GlossConditionContext;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprVariableContext;

import java.util.List;

/**
 * The expression scope one rendered chat message sees. The sender is the condition engine's
 * {@code source} role, so {@code sender.*} reads as a plain alias over it; {@code message},
 * {@code card} and {@code channel.*} arrive as supplied values.
 */
public final class ChatScope implements ExprScope {
    private static final String SENDER_PREFIX = "sender.";
    private static final String SOURCE_PREFIX = "source.";

    private final GlossConditionScope delegate;

    public ChatScope(Gloss plugin, GlossConditionContext context) {
        this.delegate = new GlossConditionScope(plugin, context);
    }

    @Override
    public Object variable(String dottedName) {
        if (dottedName.startsWith("recipient.")) {
            return delegate.variable("subject." + dottedName.substring("recipient.".length()));
        }
        return delegate.variable(dottedName.startsWith(SENDER_PREFIX)
            ? SOURCE_PREFIX + dottedName.substring(SENDER_PREFIX.length())
            : dottedName);
    }

    @Override
    public Object call(String name, List<Object> args) {
        return delegate.call(name, rewriteRoles(args));
    }

    @Override
    public ExprVariableContext variableContext() {
        return delegate.variableContext();
    }

    /** {@code hasPermission('sender', ...)} reads the same role the variables do. */
    private static List<Object> rewriteRoles(List<Object> args) {
        if (args.isEmpty() || !(args.getFirst() instanceof String role)) {
            return args;
        }
        String mapped = switch (role) {
            case "sender" -> "source";
            case "recipient" -> "subject";
            default -> role;
        };
        if (mapped.equals(role)) {
            return args;
        }
        Object[] rewritten = args.toArray();
        rewritten[0] = mapped;
        return List.of(rewritten);
    }
}
