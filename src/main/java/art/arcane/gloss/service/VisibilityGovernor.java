package art.arcane.gloss.service;

import org.bukkit.entity.Player;

/**
 * Cross-surface admission and level-of-detail arbitration per viewer. New surfaces ask for a lease
 * before spending entities on a viewer and ask for a tier before choosing how much to draw; the
 * runtime uses configured viewer, surface and aggregate budgets. {@link #passthrough()} provides
 * an explicit unrestricted implementation for callers without resource constraints.
 */
public interface VisibilityGovernor {
    enum Surface {
        HOLOGRAM,
        PANEL,
        MENU,
        PREVIEW,
        BUBBLE,
        INDICATOR,
        DROP,
        OVERLAY,
        PARTICLE,
        MARKER,
        NAMEPLATE,
        WAYPOINT,
        SURFACE
    }

    enum Tier {
        FULL,
        REDUCED,
        MINIMAL,
        CULLED
    }

    /** @return a lease to release when the entities are gone, or {@code null} when refused */
    AdmissionBudget.Lease admit(Player viewer, Surface surface, int entities);

    default AdmissionBudget.Lease renew(AdmissionBudget.Lease previous, Player viewer, Surface surface, int entities) {
        if (previous != null) {
            previous.close();
        }
        return admit(viewer, surface, entities);
    }

    Tier tier(Player viewer, Surface surface, double distanceSquared);

    static VisibilityGovernor passthrough() {
        return Passthrough.INSTANCE;
    }

    /**
     * Enforces nothing. Requests receive full detail and a releasable lease.
     */
    final class Passthrough implements VisibilityGovernor {
        private static final Passthrough INSTANCE = new Passthrough();

        private final AdmissionBudget budget = new AdmissionBudget(Integer.MAX_VALUE);

        private Passthrough() {
        }

        @Override
        public AdmissionBudget.Lease admit(Player viewer, Surface surface, int entities) {
            return budget.tryAcquire();
        }

        @Override
        public Tier tier(Player viewer, Surface surface, double distanceSquared) {
            return Tier.FULL;
        }
    }
}
