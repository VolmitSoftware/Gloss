package art.arcane.gloss.behavior;

import org.bukkit.entity.Player;

/** A runtime that can say why it chose what it chose for one viewer. */
public interface Explainable {
    /** @return the report, or null when this kind has no document with that id */
    ExplainReport explain(String id, Player viewer);
}
