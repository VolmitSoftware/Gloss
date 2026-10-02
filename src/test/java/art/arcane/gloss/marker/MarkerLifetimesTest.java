package art.arcane.gloss.marker;

import art.arcane.gloss.api.MarkerAnchor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkerLifetimesTest {
    @Test
    void repeatedProviderSnapshotsDoNotRestartTheLifetime() {
        MarkerLifetimes lifetimes = new MarkerLifetimes();
        assertTrue(lifetimes.active(marker(20L), 100L));
        assertTrue(lifetimes.active(marker(20L), 110L));
        assertFalse(lifetimes.active(marker(20L), 120L));
        lifetimes.retireAbsent(120L);
        assertFalse(lifetimes.active(marker(20L), 130L));
    }

    @Test
    void removingAndReaddingAMarkerStartsANewLifetime() {
        MarkerLifetimes lifetimes = new MarkerLifetimes();
        assertTrue(lifetimes.active(marker(10L), 0L));
        assertFalse(lifetimes.active(marker(10L), 10L));
        lifetimes.retireAbsent(20L);
        assertTrue(lifetimes.active(marker(10L), 30L));
    }

    @Test
    void movingAndRenamedMarkersDoNotRestartTheirLifetime() {
        MarkerLifetimes lifetimes = new MarkerLifetimes();
        assertTrue(lifetimes.active(marker(10L), 0L));
        assertFalse(lifetimes.active(marker(10L), 10L));
        assertFalse(lifetimes.active(marker(10L).withLabel("Changed"), 20L));
        MarkerSpec moved = marker(10L);
        MarkerSpec changed = new MarkerSpec(moved.id(),
            MarkerAnchor.position("world", 12D, 65D, 0D),
            moved.label(), moved.icon(), moved.color(), moved.distanceScale(), moved.hideWithin(),
            moved.maxDistance(), moved.beam(), moved.edge(), moved.trail(), moved.audience(), 10L, false);
        assertFalse(lifetimes.active(changed, 30L));
        assertTrue(lifetimes.active(marker(20L), 40L));
        assertTrue(lifetimes.active(marker(0L), Long.MAX_VALUE));
    }

    private static MarkerSpec marker(long lifetimeTicks) {
        MarkerSpec spec = MarkerSpec.at("target", "world", 0D, 64D, 0D);
        return new MarkerSpec(spec.id(), spec.anchor(), spec.label(), spec.icon(), spec.color(),
            spec.distanceScale(), spec.hideWithin(), spec.maxDistance(), spec.beam(), spec.edge(),
            spec.trail(), spec.audience(), lifetimeTicks, spec.waypoint());
    }
}
