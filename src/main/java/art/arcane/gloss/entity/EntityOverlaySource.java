package art.arcane.gloss.entity;

import art.arcane.gloss.api.HologramBox;
import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.gloss.condition.EntityRelationshipSnapshot;
import art.arcane.gloss.expr.ExprScope;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * A second owner for entity overlay panes. {@code EntityOverlayService} consults registered
 * sources before its own document decides a target is ineligible, which is how nameplates render
 * player panes through the same machinery without the overlay document claiming players.
 */
public interface EntityOverlaySource {
    /**
     * @param offset blocks above the target's head, replacing the overlay document's own offset
     */
    record Pane(EntityOverlayText.Prepared prepared, IconDisplayStyle style, HologramBox box,
                double offset) {
    }

    record Context(Player viewer, EntityRelationshipSnapshot relationship,
                   EntityOverlayText.Snapshot snapshot, ExprScope scope) { }

    record ScanPolicy(double range, int subjects, int intervalTicks) {
        public static final ScanPolicy INHERIT = new ScanPolicy(0, 0, 0);

        public ScanPolicy {
            if (!Double.isFinite(range) || range < 0 || range > 64 || subjects < 0 || subjects > 256
                || intervalTicks < 0 || intervalTicks > 200) {
                throw new IllegalArgumentException("Invalid overlay source scan policy");
            }
        }
    }

    boolean wants(EntityRelationshipSnapshot target);

    Pane prepare(Context context);

    default void displayed(Player viewer, EntityRelationshipSnapshot relationship) {
    }

    default ScanPolicy scanPolicy() {
        return ScanPolicy.INHERIT;
    }

    default boolean includesSelf() {
        return false;
    }

    /**
     * The pane for this pair has stopped being drawn: the subject left range, left the world, or
     * the viewer went away. A source that holds per-pair state outside the pane, the way nameplate
     * suppression holds a scoreboard team, releases it here. Ids rather than live objects, because
     * every caller is a teardown path where one or both may already be gone.
     */
    default void retired(UUID viewerId, UUID targetId) {
    }
}
