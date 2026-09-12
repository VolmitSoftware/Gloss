package art.arcane.gloss.state;

import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.expr.ExprVariableNamespace;
import org.bukkit.World;

import java.util.Objects;

/**
 * {@code state.<key>} in every expression. The key's declared scope picks the owner: a player key
 * reads the context viewer, a world key the viewer's world (or the context location), a global key
 * nothing. An undeclared key, or a scope the context cannot supply, is unknown.
 */
public final class StateNamespace implements ExprVariableNamespace {
    public static final String PREFIX = "state";

    private final StateStore store;

    public StateNamespace(StateStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public Object resolve(String suffix, ExprVariableContext context) {
        StateSchema schema = store.declarations().get(suffix);
        if (schema == null) {
            return null;
        }
        return switch (schema.scope()) {
            case GLOBAL -> store.get(StateScope.GLOBAL, null, suffix);
            case PLAYER -> context.viewer() == null ? null
                : store.get(StateScope.PLAYER, context.viewer().getUniqueId(), suffix);
            case WORLD -> {
                World world = worldOf(context);
                yield world == null ? null : store.get(StateScope.WORLD, world.getUID(), suffix);
            }
        };
    }

    private static World worldOf(ExprVariableContext context) {
        if (context.viewer() != null) {
            return context.viewer().getWorld();
        }
        if (context.location() != null && context.location().getWorld() != null) {
            return context.location().getWorld();
        }
        return context.subject() == null ? null : context.subject().getWorld();
    }
}
