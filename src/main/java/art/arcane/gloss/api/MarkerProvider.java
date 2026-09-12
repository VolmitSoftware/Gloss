package art.arcane.gloss.api;

import art.arcane.gloss.marker.MarkerSpec;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Supplies markers for one viewer on demand. Gloss samples every registered provider on the
 * marker driver's cadence, so the implementation must be cheap and must not block; returning
 * {@code null} or an empty list contributes nothing.
 */
@FunctionalInterface
public interface MarkerProvider {
    List<MarkerSpec> markersFor(Player viewer);
}
