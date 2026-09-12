package art.arcane.gloss.expr;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * The roles a registered variable namespace may read. Any component may be null: text surfaces
 * only know the viewer, conditions know all three roles plus a location.
 */
public record ExprVariableContext(Player viewer, Entity subject, Entity source, Location location) {
    private static final ExprVariableContext EMPTY = new ExprVariableContext(null, null, null, null);

    public static ExprVariableContext empty() {
        return EMPTY;
    }

    public static ExprVariableContext viewer(Player viewer) {
        return viewer == null ? EMPTY : new ExprVariableContext(viewer, viewer, null, viewer.getLocation());
    }
}
