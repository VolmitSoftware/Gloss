package art.arcane.gloss.hologram;

import art.arcane.gloss.hologram.CharacterizationHarness.DisplayHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.PlayerHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import art.arcane.volmlib.util.localization.LanguageAudience;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramServiceHygieneTest {
    @TempDir
    File dataFolder;

    private CharacterizationHarness harness;
    private WorldState world;
    private PlayerHandle alice;

    @BeforeEach
    void setUp() {
        harness = new CharacterizationHarness(dataFolder);
        world = harness.world("overworld");
        alice = harness.join("Alice", world, 1.0D, 64.0D, 1.0D);
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
        HologramService.ViewerWorkQueue queue = new HologramService.ViewerWorkQueue(harness.service, alice.uuid);
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

    @Test
    void ownerDrainPreservesAudienceAndProcessesWorkEnqueuedDuringTheDrain() {
        List<String> rendered = new ArrayList<>();
        UUID outerAudience = UUID.randomUUID();
        long before = harness.service.viewerWorkDispatchCount();
        try (LanguageAudience.Scope audience = LanguageAudience.open(outerAudience)) {
            harness.service.runViewerWork(alice.proxy, alice.uuid, "first", () -> {
                assertEquals(alice.uuid, LanguageAudience.current());
                rendered.add("first");
                harness.service.runViewerWork(alice.proxy, alice.uuid, "next", () -> rendered.add("next"));
            });
            assertEquals(outerAudience, LanguageAudience.current());
        }
        assertEquals(List.of("first", "next"), rendered);
        assertEquals(before + 1, harness.service.viewerWorkDispatchCount());
        assertTrue(harness.immediateTasks.isEmpty());
    }

    @Test
    void foreignOwnerDrainCoalescesPendingKeysAndRestoresAudience() {
        harness.ownsThread = false;
        harness.deferImmediateTasks = true;
        List<Integer> rendered = new ArrayList<>();
        harness.service.runViewerWork(alice.proxy, alice.uuid, "same", () -> rendered.add(1));
        harness.service.runViewerWork(alice.proxy, alice.uuid, "same", () -> {
            assertEquals(alice.uuid, LanguageAudience.current());
            rendered.add(2);
        });
        assertTrue(rendered.isEmpty());
        assertEquals(1, harness.immediateTasks.size());
        harness.ownsThread = true;
        UUID outerAudience = UUID.randomUUID();
        try (LanguageAudience.Scope audience = LanguageAudience.open(outerAudience)) {
            harness.drainImmediate();
            assertEquals(outerAudience, LanguageAudience.current());
        }
        assertEquals(List.of(2), rendered);
    }

    @Test
    void retiredOwnerAndInactivePluginDiscardWorkWithoutBlockingLaterRefresh() {
        AtomicInteger rendered = new AtomicInteger();
        alice.online = false;
        harness.service.runViewerWork(alice.proxy, alice.uuid, "same", rendered::incrementAndGet);
        alice.online = true;
        harness.enabled(false);
        harness.service.runViewerWork(alice.proxy, alice.uuid, "same", rendered::incrementAndGet);
        assertEquals(0, rendered.get());
        harness.enabled(true);
        harness.service.runViewerWork(alice.proxy, alice.uuid, "same", rendered::incrementAndGet);
        assertEquals(1, rendered.get());
    }

    @Test
    void staleForeignOwnerRetirementDoesNotRemoveAReconnectedViewersQueue() {
        harness.ownsThread = false;
        harness.deferImmediateTasks = true;
        AtomicInteger oldRefreshes = new AtomicInteger();
        AtomicInteger newRefreshes = new AtomicInteger();
        harness.service.runViewerWork(alice.proxy, alice.uuid, "same", oldRefreshes::incrementAndGet);
        harness.quit(alice);
        PlayerHandle returning = harness.join("Alice", alice.uuid, world, 1.0D, 64.0D, 1.0D);
        harness.service.runViewerWork(returning.proxy, returning.uuid, "same", newRefreshes::incrementAndGet);
        assertEquals(2, harness.immediateTasks.size());
        harness.ownsThread = true;
        harness.drainImmediate();
        assertEquals(0, oldRefreshes.get());
        assertEquals(1, newRefreshes.get());
        harness.service.runViewerWork(returning.proxy, returning.uuid, "same", newRefreshes::incrementAndGet);
        assertEquals(2, newRefreshes.get());
    }

    @Test
    void failedOwnerWorkRestoresAudienceAndAllowsAnotherRefresh() {
        UUID outerAudience = UUID.randomUUID();
        AtomicInteger rendered = new AtomicInteger();
        try (LanguageAudience.Scope audience = LanguageAudience.open(outerAudience)) {
            harness.service.runViewerWork(alice.proxy, alice.uuid, "failure", () -> {
                harness.service.runViewerWork(alice.proxy, alice.uuid, "next", rendered::incrementAndGet);
                throw new IllegalStateException("Expected viewer work failure");
            });
            assertEquals(outerAudience, LanguageAudience.current());
        }
        assertEquals(1, rendered.get());
        harness.service.runViewerWork(alice.proxy, alice.uuid, "later", rendered::incrementAndGet);
        assertEquals(2, rendered.get());
    }
}
