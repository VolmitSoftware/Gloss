package art.arcane.gloss.service;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * One governor lease per viewer for a surface that redraws its whole set every pass, like markers.
 * Renewal adjusts the existing reservation; refused changes retain it until teardown succeeds.
 *
 * <p>A refusal has to tear the render down. Entities left drawn that the governor no longer counts
 * make the budget under-count permanently: the next pass asks again, is refused again, and the
 * viewer keeps paying for entities nobody is accounting for, which is the opposite of what the
 * budget is for.
 */
public final class ViewerLeases {
    private final VisibilityGovernor.Surface surface;
    private final ConcurrentMap<UUID, AdmissionBudget.Lease> leases = new ConcurrentHashMap<>();

    public ViewerLeases(VisibilityGovernor.Surface surface) {
        this.surface = Objects.requireNonNull(surface, "surface");
    }

    /**
     * @param onRefused tears down whatever this viewer already has drawn
     * @return whether the viewer may draw
     */
    public boolean admit(VisibilityGovernor governor, Player viewer, int entities, Runnable onRefused) {
        UUID viewerId = viewer.getUniqueId();
        AdmissionBudget.Lease previous = leases.get(viewerId);
        AdmissionBudget.Lease lease = governor.renew(previous, viewer, surface, entities);
        if (lease == null) {
            onRefused.run();
            if (previous != null && leases.remove(viewerId, previous)) {
                previous.close();
            }
            return false;
        }
        leases.put(viewerId, lease);
        return true;
    }

    public void release(UUID viewerId) {
        AdmissionBudget.Lease lease = leases.remove(viewerId);
        if (lease != null) {
            lease.close();
        }
    }

    public boolean holds(UUID viewerId) {
        return leases.containsKey(viewerId);
    }

    public void clear() {
        for (UUID viewerId : List.copyOf(leases.keySet())) {
            release(viewerId);
        }
    }
}
