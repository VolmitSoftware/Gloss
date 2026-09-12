package art.arcane.gloss.api;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.beam.BeamService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Beams and trails for other plugins. Both suppliers are sampled on the beam driver's cadence, so
 * a beam follows a moving anchor without the caller doing anything; returning null from either
 * ends the beam.
 */
public final class Beams {
    private Beams() {
    }

    /**
     * @param lifetimeTicks ticks before the beam ends on its own, or {@code 0} to run until cancelled
     * @return a handle, or null when the beam service is not running
     */
    public static BeamHandle link(Supplier<Location> from, Supplier<Location> to, BeamSpec spec,
                                  long lifetimeTicks, Set<UUID> viewers) {
        BeamService service = service();
        return service == null ? null : service.link(from, to, spec, lifetimeTicks, viewers);
    }

    /** Walks a particle line from {@code from} to {@code to} for this viewer alone. */
    public static void trail(Player viewer, Location from, Location to, String particle,
                             double spacing, int maxPoints) {
        BeamService service = service();
        if (service != null) {
            service.trail(viewer, from, to, particle, spacing, maxPoints);
        }
    }

    private static BeamService service() {
        Gloss plugin = Gloss.instance;
        return plugin == null ? null : plugin.service(BeamService.class);
    }
}
