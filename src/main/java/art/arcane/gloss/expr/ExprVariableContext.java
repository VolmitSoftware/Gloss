package art.arcane.gloss.expr;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * The roles a registered variable namespace may read. Any component may be null: text surfaces
 * only know the viewer, conditions know all three roles plus a location.
 */
public record ExprVariableContext(Player viewer, Entity subject, Entity source, Location location,
                                  Map<String, ExprRoleSnapshot> snapshots) {
    private static final ExprVariableContext EMPTY = new ExprVariableContext(null, null, null, null);

    public ExprVariableContext {
        snapshots = Map.copyOf(snapshots);
    }

    public ExprVariableContext(Player viewer, Entity subject, Entity source, Location location) {
        this(viewer, subject, source, location, Map.of());
    }

    public Player viewer() {
        return (Player) checked("viewer", viewer);
    }

    public Entity subject() {
        return checked("subject", subject);
    }

    public Entity source() {
        return checked("source", source);
    }

    public Object roleValue(String role, String property) {
        ExprRoleSnapshot snapshot = snapshots.get(role);
        if (snapshot == null) {
            throw new IllegalStateException("No captured role is available for " + role);
        }
        return snapshot.variable("subject." + property);
    }

    private Entity checked(String role, Entity entity) {
        ExprRoleSnapshot snapshot = snapshots.get(role);
        if (entity != null && snapshot != null && !snapshot.ownsCurrentThread()) {
            throw new IllegalStateException("Live " + role + " access requires its owning region; "
                + "expression providers must use context.roleValue(role, property) for captured values");
        }
        return entity;
    }

    public static ExprVariableContext empty() {
        return EMPTY;
    }

    public static ExprVariableContext viewer(Player viewer) {
        return viewer == null ? EMPTY : new ExprVariableContext(viewer, viewer, null, viewer.getLocation());
    }
}
