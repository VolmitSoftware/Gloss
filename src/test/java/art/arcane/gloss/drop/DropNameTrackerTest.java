package art.arcane.gloss.drop;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DropNameTrackerTest {

    @Test
    void trackedDropsCountUntilTheyAreForgotten() {
        Set<UUID> world = new HashSet<>();
        DropNameTracker tracker = new DropNameTracker(world::contains);
        UUID drop = UUID.randomUUID();

        world.add(drop);
        tracker.track(drop);
        assertEquals(1, tracker.size());

        tracker.forget(drop);
        assertEquals(0, tracker.size());
    }

    @Test
    void trackingIsIdempotentSoAMergeRefreshDoesNotDoubleCount() {
        DropNameTracker tracker = new DropNameTracker(id -> true);
        UUID drop = UUID.randomUUID();

        tracker.track(drop);
        tracker.track(drop);

        assertEquals(1, tracker.size());
    }

    @Test
    void aFullSweepDropsEveryDeadEntryAndKeepsEveryLiveOne() {
        Set<UUID> world = new HashSet<>();
        DropNameTracker tracker = new DropNameTracker(world::contains);
        List<UUID> live = new ArrayList<>();
        List<UUID> dead = new ArrayList<>();

        for (int index = 0; index < 50; index++) {
            UUID alive = UUID.randomUUID();
            world.add(alive);
            live.add(alive);
            tracker.track(alive);

            UUID gone = UUID.randomUUID();
            dead.add(gone);
            tracker.track(gone);
        }

        for (int pass = 0; pass < 20; pass++) {
            tracker.prune(16);
        }

        assertEquals(live.size(), tracker.size());
        for (UUID gone : dead) {
            tracker.track(gone);
        }
        assertEquals(live.size() + dead.size(), tracker.size());
    }

    @Test
    void aSinglePassNeverInspectsMoreThanItsBudget() {
        AtomicInteger inspections = new AtomicInteger();
        DropNameTracker tracker = new DropNameTracker(id -> {
            inspections.incrementAndGet();
            return true;
        });
        for (int index = 0; index < 500; index++) {
            tracker.track(UUID.randomUUID());
        }

        tracker.prune(8);

        assertEquals(8, inspections.get());
        assertEquals(500, tracker.size());
    }

    @Test
    void repeatedPassesAdvanceTheCursorInsteadOfRescanningTheHead() {
        Set<UUID> inspected = new HashSet<>();
        DropNameTracker tracker = new DropNameTracker(id -> {
            inspected.add(id);
            return true;
        });
        for (int index = 0; index < 40; index++) {
            tracker.track(UUID.randomUUID());
        }

        for (int pass = 0; pass < 5; pass++) {
            tracker.prune(8);
        }

        assertEquals(40, inspected.size());
    }

    @Test
    void pruningAnEmptyTrackerIsAFreeNoOp() {
        AtomicInteger inspections = new AtomicInteger();
        DropNameTracker tracker = new DropNameTracker(id -> {
            inspections.incrementAndGet();
            return true;
        });

        tracker.prune(64);

        assertEquals(0, inspections.get());
        assertEquals(0, tracker.size());
    }

    @Test
    void ownerInspectionAdvancesInBoundedCohortsWithoutRemovingEntries() {
        DropNameTracker tracker = new DropNameTracker();
        Set<UUID> inspected = new HashSet<>();
        for (int index = 0; index < 40; index++) {
            tracker.track(UUID.randomUUID());
        }

        for (int pass = 0; pass < 5; pass++) {
            tracker.inspect(8, inspected::add);
        }

        assertEquals(40, inspected.size());
        assertEquals(40, tracker.size());
    }

    @Test
    void clearingResetsBothTheSetAndTheCursor() {
        DropNameTracker tracker = new DropNameTracker(id -> false);
        for (int index = 0; index < 10; index++) {
            tracker.track(UUID.randomUUID());
        }
        tracker.prune(4);

        tracker.clear();
        assertEquals(0, tracker.size());

        UUID drop = UUID.randomUUID();
        tracker.track(drop);
        tracker.prune(4);
        assertEquals(0, tracker.size());
    }
}
