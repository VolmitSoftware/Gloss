package art.arcane.gloss.marker;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.MarkerAnchor;
import art.arcane.gloss.config.GlossConfigFile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnchorSnapshotsTest {
    private static final MarkerAnchor FIRST = MarkerAnchor.entity(UUID.randomUUID());
    private static final MarkerAnchor SECOND = MarkerAnchor.player("Traveler");
    private static final AnchorSnapshots.Snapshot POSITION = new AnchorSnapshots.Snapshot(UUID.randomUUID(), 1, 2, 3);

    @Test
    void sharesOnePendingOwnerCaptureAcrossViewersAndExpiresOldPositions() {
        Fixture fixture = new Fixture(4);
        assertNull(fixture.store.snapshot(FIRST));
        assertNull(fixture.store.snapshot(FIRST));
        assertEquals(1, fixture.callbacks.size());
        fixture.callbacks.getFirst().accept(POSITION);
        assertEquals(POSITION, fixture.store.snapshot(FIRST));
        fixture.tick(2);
        assertEquals(POSITION, fixture.store.snapshot(FIRST));
        assertEquals(2, fixture.callbacks.size());
        fixture.tick(5);
        assertNull(fixture.store.snapshot(FIRST));
        assertEquals(2, fixture.callbacks.size());
        fixture.callbacks.getLast().accept(POSITION);
        assertEquals(POSITION, fixture.store.snapshot(FIRST));
    }

    @Test
    void pendingCapturesRemainBoundedEvenWhenTheCacheEvictsTheirAnchors() {
        Fixture fixture = new Fixture(1);
        fixture.store.snapshot(FIRST);
        Consumer<AnchorSnapshots.Snapshot> old = fixture.callbacks.getFirst();
        for (int index = 0; index < 100; index++) {
            assertNull(fixture.store.snapshot(MarkerAnchor.entity(UUID.randomUUID())));
        }
        assertEquals(1, fixture.callbacks.size());
        old.accept(POSITION);
        assertNull(fixture.store.snapshot(FIRST));
        assertEquals(2, fixture.callbacks.size());
        fixture.store.close();
        fixture.callbacks.getLast().accept(POSITION);
        assertNull(fixture.store.snapshot(FIRST));
        assertEquals(2, fixture.callbacks.size());
    }

    @Test
    void refusedOrRetiredSchedulingRetriesWithoutReadingTheForeignEntity() {
        Fixture fixture = new Fixture(4);
        fixture.accepted = false;
        assertNull(fixture.store.snapshot(FIRST));
        fixture.tick(2);
        fixture.accepted = true;
        assertNull(fixture.store.snapshot(FIRST));
        fixture.callbacks.getFirst().accept(POSITION);
        assertNull(fixture.store.snapshot(FIRST));
        fixture.retired.getLast().run();
        fixture.tick(4);
        assertNull(fixture.store.snapshot(FIRST));
        fixture.callbacks.getLast().accept(POSITION);
        assertEquals(POSITION, fixture.store.snapshot(FIRST));
    }

    @Test
    void snapshotLimitsNormalizeAndThePreviousMarkerConstructorKeepsDefaults() {
        GlossConfigFile file = new GlossConfigFile();
        file.markers.anchorSnapshotTicks = 5000;
        file.markers.anchorMaxAgeTicks = 0;
        file.markers.anchorCacheEntries = 0;
        file.normalize();
        GlossConfig.Markers markers = GlossConfig.from(file).modules().markers();
        assertEquals(1200, markers.anchorSnapshotTicks());
        assertEquals(1200, markers.anchorMaxAgeTicks());
        assertEquals(16, markers.anchorCacheEntries());
        assertEquals(4096, new GlossConfig.Markers(true, 12, 256).anchorCacheEntries());
    }

    private static final class Fixture {
        private final AtomicLong now = new AtomicLong();
        private final List<Consumer<AnchorSnapshots.Snapshot>> callbacks = new ArrayList<>();
        private final List<Runnable> retired = new ArrayList<>();
        private final AnchorSnapshots store;
        private boolean accepted = true;

        private Fixture(int capacity) {
            store = new AnchorSnapshots(new AnchorSnapshots.Dependencies((anchor, completed, retirement) -> {
                callbacks.add(completed);
                retired.add(retirement);
                return accepted;
            }, () -> new AnchorSnapshots.Settings(2, 4, capacity), now::get));
        }

        private void tick(long tick) {
            now.set(tick * 50_000_000L);
        }
    }
}
