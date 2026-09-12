package art.arcane.gloss.marker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Distance filtering and the nearest-N cut, kept pure so the ordering is testable without a world.
 * Ties break on id so a viewer standing between two equidistant markers does not see them swap
 * every pass.
 */
public final class MarkerSelection {
    private static final Comparator<MarkerCandidate> NEAREST_FIRST =
        Comparator.comparingDouble(MarkerCandidate::distance).thenComparing(MarkerCandidate::id);

    private MarkerSelection() {
    }

    public static List<MarkerCandidate> select(List<MarkerCandidate> candidates, int maxPerViewer) {
        List<MarkerCandidate> eligible = new ArrayList<>(candidates.size());
        for (MarkerCandidate candidate : candidates) {
            MarkerSpec spec = candidate.spec();
            if (candidate.distance() < spec.hideWithin() || candidate.distance() > spec.maxDistance()) {
                continue;
            }
            eligible.add(candidate);
        }
        eligible.sort(NEAREST_FIRST);
        return List.copyOf(eligible.subList(0, Math.min(eligible.size(), Math.max(0, maxPerViewer))));
    }
}
