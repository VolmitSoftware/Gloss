package art.arcane.gloss.dialog;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.expr.ExprScope;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * The scope a dialog's texts and gates evaluate in: this open's {@code args}, the running response's
 * {@code input} answers and {@code dialog.id}, and the viewer's own condition scope behind them.
 *
 * <p>The viewer scope is built on first use. Most dialogs are plain text and never ask for one, and
 * building it eagerly would read the viewer's location once per rendered string.
 */
final class DialogScope implements ExprScope {
    private static final String ARGS = "args.";
    private static final String INPUT = "input.";
    private static final String DIALOG_ID = "dialog.id";

    private final Player viewer;
    private final Map<String, Object> args;
    private ExprScope delegate;
    private boolean delegateResolved;

    DialogScope(Player viewer, Map<String, Object> args) {
        this.viewer = viewer;
        this.args = args == null ? Map.of() : args;
    }

    @Override
    public Object variable(String dottedName) {
        if (dottedName == null) {
            return null;
        }
        if (dottedName.startsWith(ARGS)) {
            return args.get(dottedName.substring(ARGS.length()));
        }
        if (dottedName.startsWith(INPUT)) {
            InputNamespace.Binding binding = InputNamespace.active();
            return binding == null ? null : binding.inputs().get(dottedName.substring(INPUT.length()));
        }
        if (dottedName.equals(DIALOG_ID)) {
            InputNamespace.Binding binding = InputNamespace.active();
            return binding == null ? null : binding.dialogId();
        }
        ExprScope scope = delegate();
        return scope == null ? null : scope.variable(dottedName);
    }

    @Override
    public Object call(String name, List<Object> callArgs) {
        ExprScope scope = delegate();
        return scope == null ? null : scope.call(name, callArgs);
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
