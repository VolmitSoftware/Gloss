package art.arcane.gloss.entity;

import art.arcane.gloss.api.HologramBox;
import art.arcane.gloss.api.IconDisplayStyle;
import org.bukkit.entity.LivingEntity;
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

    boolean wants(LivingEntity target);

    /** @return the pane this source wants drawn, or null to draw nothing for this viewer */
    Pane prepare(Player viewer, LivingEntity target, EntityOverlayText.Snapshot snapshot);

    /**
     * The pane for this pair has stopped being drawn: the subject left range, left the world, or
     * the viewer went away. A source that holds per-pair state outside the pane, the way nameplate
     * suppression holds a scoreboard team, releases it here. Ids rather than live objects, because
     * every caller is a teardown path where one or both may already be gone.
     */
    default void retired(UUID viewerId, UUID targetId) {
    }
}
