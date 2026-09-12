package art.arcane.gloss.chat;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.GlossConditionContext;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprVariableContext;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * The expression scope one rendered chat message sees. The sender is the condition engine's
 * {@code source} role, so {@code sender.*} reads as a plain alias over it; {@code message},
 * {@code card} and {@code channel.*} arrive as supplied values.
 */
public final class ChatScope implements ExprScope {
    private static final String SENDER_PREFIX = "sender.";
    private static final String SOURCE_PREFIX = "source.";

    private final GlossConditionScope delegate;

    public ChatScope(Gloss plugin, Player viewer, Player sender, Map<String, Object> values) {
        this.delegate = new GlossConditionScope(plugin,
            new GlossConditionContext(viewer, viewer, sender, location(viewer, sender), values));
    }

    @Override
    public Object variable(String dottedName) {
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
        if (args.isEmpty() || !"sender".equals(args.getFirst())) {
            return args;
        }
        Object[] rewritten = args.toArray();
        rewritten[0] = "source";
        return List.of(rewritten);
    }

    private static org.bukkit.Location location(Player viewer, Player sender) {
        if (viewer != null) {
            return viewer.getLocation();
        }
        return sender == null ? null : sender.getLocation();
    }
}
