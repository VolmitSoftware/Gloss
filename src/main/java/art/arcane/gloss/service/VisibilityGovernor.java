package art.arcane.gloss.service;

import org.bukkit.entity.Player;

/**
 * Cross-surface admission and level-of-detail arbitration per viewer. New surfaces ask for a lease
 * before spending entities on a viewer and ask for a tier before choosing how much to draw; the
 * seam ships {@link #passthrough()}, which admits everything at full detail and exists so the
 * call sites are in place when the real arbiter lands.
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

    Tier tier(Player viewer, Surface surface, double distanceSquared);

    static VisibilityGovernor passthrough() {
        return Passthrough.INSTANCE;
    }

    /**
     * Enforces nothing. {@code entities} is ignored and no request is ever refused, so the only
     * limits in the build are each surface's own: the marker cap per viewer, the interaction hitbox cap, and the
     * entity-overlay caps. Nothing arbitrates across surfaces. Read a call to {@link #admit} as a
     * seam waiting for an arbiter, never as a bound on the worst case.
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
