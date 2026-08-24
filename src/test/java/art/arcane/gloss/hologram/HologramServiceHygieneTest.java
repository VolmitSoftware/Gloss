package art.arcane.gloss.hologram;

import art.arcane.gloss.hologram.CharacterizationHarness.DisplayHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramServiceHygieneTest {
    @TempDir
    File dataFolder;

    private CharacterizationHarness harness;
    private WorldState world;

    @BeforeEach
    void setUp() {
        harness = new CharacterizationHarness(dataFolder);
        world = harness.world("overworld");
        harness.join("Alice", world, 1.0D, 64.0D, 1.0D);
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void entitiesLoadPurgesOrphansInlineWithoutSchedulingWork() {
        DisplayHandle taggedOrphan = harness.orphanDisplay(world, true, false);
        DisplayHandle markedOrphan = harness.orphanDisplay(world, false, true);
        DisplayHandle foreignDisplay = harness.orphanDisplay(world, false, false);

        harness.fireEntitiesLoad(world);

        assertTrue(taggedOrphan.removed, "the purge must run on the event thread");
        assertTrue(markedOrphan.removed);
        assertFalse(foreignDisplay.removed);
        assertTrue(harness.delayedTasks.isEmpty(), "the purge must not schedule follow-up work");
        assertTrue(harness.schedulerErrors.isEmpty());
    }

    @Test
    void leaseSweepDropsEntitiesThatDiedOutsideTheService() {
        TemporaryHologramDisplay temporary = harness.temporary("t-lease", harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(List.of("hi"));
        temporary.drive(true);
        DisplayHandle display = harness.onlySpawned(world);
        assertEquals(1, harness.service.activeEntityCount());

        display.removed = true;
        harness.driveHolograms();

        assertEquals(0, harness.service.activeEntityCount(),
            "entities removed outside the service must stop counting as leased");
    }

    @Test
    void leaseSweepKeepsLiveEntities() {
        TemporaryHologramDisplay temporary = harness.temporary("t-live", harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(List.of("hi"));
        temporary.drive(true);

        harness.driveHolograms();
        harness.driveHolograms();

        assertEquals(1, harness.service.activeEntityCount());
    }

    @Test
    void expiringTemporariesDeregisterDuringTheDrivePass() {
        for (int index = 0; index < 3; index++) {
            TemporaryHologramDisplay temporary = harness.temporary("t-expire-" + index,
                harness.at(world, 0.5D, 64.0D, 0.5D), 0L);
            temporary.setLines(List.of("hi"));
        }
        assertEquals(3, harness.service.temporaryCount());

        harness.driveTemporaries();

        assertEquals(0, harness.service.temporaryCount(),
            "the drive pass must tolerate temporaries deregistering while it iterates");
        assertTrue(harness.liveSpawned(world).isEmpty());
    }

    @Test
    void retiredEntityDriveReleasesLatchAndRetriesAtTheAnchor() {
        TemporaryHologramDisplay temporary = harness.temporary("t-retired",
            harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(List.of("hi"));
        temporary.drive(true);
        DisplayHandle retired = harness.onlySpawned(world);
        retired.removed = true;

        harness.driveTemporary(temporary, true);
        DisplayHandle replacement = harness.onlySpawned(world);
        assertTrue(replacement != retired);

        harness.driveTemporary(temporary, true);
        assertEquals(1, harness.liveSpawned(world).size(),
            "a retired entity callback must not leave the drive latch stuck");
    }

    @Test
    void rejectedSchedulerRetirementContinuationRunsOnce() {
        AtomicInteger continuations = new AtomicInteger();
        Runnable retirement = TemporaryHologramDisplay.once(continuations::incrementAndGet);

        retirement.run();
        retirement.run();

        assertEquals(1, continuations.get(),
            "scheduler rejection and its false return must share one retirement continuation");
    }

    @Test
    void viewerWorkQueueRetainsOnlyTheLatestRefreshPerHologram() {
        HologramService.ViewerWorkQueue queue = new HologramService.ViewerWorkQueue();
        AtomicInteger rendered = new AtomicInteger();

        for (int refresh = 1; refresh <= 1_000; refresh++) {
            int value = refresh;
            queue.put("same-hologram", () -> rendered.set(value));
        }

        assertEquals(1, queue.pendingCount(),
            "a lagging player region must retain one latest refresh per hologram");
        queue.remove("same-hologram").run();
        assertEquals(1_000, rendered.get());
    }
}
