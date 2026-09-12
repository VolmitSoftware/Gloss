package art.arcane.gloss.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.expr.ExprVariableNamespace;
import art.arcane.gloss.inventory.InventoryMenuService;
import org.bukkit.entity.Player;

/**
 * The {@code session.<name>} namespace: the variables of whatever surface the viewer has open. A
 * hologram menu is checked first because it is the surface whose components re-render off these
 * values; a chest window answers when no menu is open.
 */
public final class SessionNamespace implements ExprVariableNamespace {
    public static final String PREFIX = "session";

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public Object resolve(String suffix, ExprVariableContext context) {
        SessionVariables variables = variablesOf(context);
        return variables == null ? null : variables.get(suffix);
    }

    /** The open surface's variables for this viewer, or null when nothing is open. */
    static SessionVariables variablesOf(ExprVariableContext context) {
        Player viewer = context == null ? null : context.viewer();
        Gloss plugin = Gloss.instance;
        if (viewer == null || plugin == null) {
            return null;
        }
        MenuSessionManager sessions = plugin.getSessionManager();
        SessionVariables variables = sessions == null ? null : sessions.sessionVariables(viewer.getUniqueId());
        if (variables != null) {
            return variables;
        }
        InventoryMenuService inventories = InventoryMenuService.active();
        return inventories == null ? null : inventories.sessionVariables(viewer.getUniqueId());
    }
}
