package art.arcane.gloss.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.expr.ExprScope;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The scope a menu's own expressions see: this session's variables and arguments in front of the
 * viewer's condition scope. Sessions resolve their own state directly rather than through the
 * registered {@code session} namespace, because a session is expanding its components before the
 * manager knows it exists.
 */
public final class SessionScope implements ExprScope {
    private static final String SESSION = "session.";
    private static final String ARGS = "args.";

    private final Player viewer;
    private final SessionVariables variables;
    private ExprScope delegate;
    private boolean delegateResolved;

    public SessionScope(Player viewer, SessionVariables variables) {
        this.viewer = viewer;
        this.variables = variables;
    }

    @Override
    public Object variable(String dottedName) {
        if (dottedName == null) {
            return null;
        }
        if (dottedName.startsWith(SESSION)) {
            return variables.get(dottedName.substring(SESSION.length()));
        }
        if (dottedName.startsWith(ARGS)) {
            return variables.args().get(dottedName.substring(ARGS.length()));
        }
        ExprScope scope = delegate();
        return scope == null ? null : scope.variable(dottedName);
    }

    @Override
    public Object call(String name, List<Object> args) {
        ExprScope scope = delegate();
        return scope == null ? null : scope.call(name, args);
    }

    private ExprScope delegate() {
        if (!delegateResolved) {
            delegateResolved = true;
            Gloss plugin = Gloss.instance;
            delegate = plugin == null || viewer == null || plugin.text() == null
                ? null
                : GlossConditionScope.viewer(plugin, viewer);
        }
        return delegate;
    }
}
