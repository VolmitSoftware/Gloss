package art.arcane.gloss.hologram;

import art.arcane.gloss.api.Hologram;
import art.arcane.gloss.hologram.CharacterizationHarness.DisplayHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization pins for {@link HologramService} lifecycle, driver, and purge behavior
 * (STEP 1, Char-1). Frozen contract for Lane A (A7 purge rework, A8 lease reconcile,
 * A13 registry swap) and the driver-cadence work.
 */
class CharacterizationHologramServiceTest {
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

    private File docFile(String id) {
        return new File(new File(dataFolder, "holograms"), id + ".json");
    }

    @Test
    void createIsIdempotentAndRegistersTheHologram() {
        Hologram first = harness.service.create("greeter", harness.at(world, 0.5D, 64.0D, 0.5D));
        Hologram second = harness.service.create("greeter", harness.at(world, 9.0D, 64.0D, 9.0D));

        assertSame(first, second, "creating an existing id must return the registered instance");
        assertTrue(harness.service.has("greeter"));
        assertSame(first, harness.service.get("greeter"));
        assertEquals(1, harness.service.hologramCount());
        assertEquals(1, harness.service.all().size());
        assertNull(harness.service.get(null));
        assertFalse(harness.service.has(null));
    }

    @Test
    void unsafeIdsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> harness.service.create(" ", harness.at(world, 0.5D, 64.0D, 0.5D)));
        assertThrows(IllegalArgumentException.class,
            () -> harness.service.create("a/b", harness.at(world, 0.5D, 64.0D, 0.5D)));
        assertThrows(IllegalArgumentException.class,
            () -> harness.service.create("..\\evil", harness.at(world, 0.5D, 64.0D, 0.5D)));
        assertThrows(NullPointerException.class,
            () -> harness.service.create(null, harness.at(world, 0.5D, 64.0D, 0.5D)));
        assertThrows(IllegalArgumentException.class, () -> harness.service.delete("a/../b"));
    }

    @Test
    void renderStaticLinesJoinsWithNewlinesAndAppliesColors() {
        assertEquals("§aA§r\nB", harness.service.renderStaticLines(List.of("&aA", "B")));
        assertEquals("solo", harness.service.renderStaticLines(List.of("solo")));
        assertEquals("", harness.service.renderStaticLines(List.of()));
        assertEquals("a§r\n§r\nb", harness.service.renderStaticLines(List.of("a", "", "b")),
            "blank lines must be preserved as empty segments");
    }

    @Test
    void driversDespawnWhileDisabledAndRespawnWhenReenabled() {
        PersistentHologram hologram = harness.persistent("driver-holo", harness.at(world, 0.5D, 64.0D, 0.5D));
        hologram.setLines(List.of("hi"));
        TemporaryHologramDisplay temporary = harness.temporary("driver-temp", harness.at(world, 4.5D, 64.0D, 4.5D), 60_000L);
        temporary.setLines(List.of("tmp"));

        harness.driveHolograms();
        harness.driveTemporaries();
        assertEquals(2, harness.liveSpawned(world).size(), "enabled drivers must spawn both display kinds");

        harness.configure(file -> file.features.holograms = false);
        harness.driveHolograms();
        harness.driveTemporaries();
        assertTrue(harness.liveSpawned(world).isEmpty(), "disabled drivers must despawn everything");
        assertEquals(1, harness.service.temporaryCount(), "disabling must not destroy temporaries");
        assertEquals(1, harness.service.hologramCount());

        harness.configure(file -> file.features.holograms = true);
        harness.driveHolograms();
        harness.driveTemporaries();
        assertEquals(2, harness.liveSpawned(world).size(), "re-enabling must respawn both display kinds");
    }

    @Test
    void driverTasksScheduleAtTheConfiguredCadences() {
        harness.startTasks();

        List<Long> delays = new ArrayList<>();
        for (CharacterizationHarness.DelayedTask delayed : harness.delayedTasks) {
            delays.add(delayed.delayTicks());
        }
        assertEquals(List.of(10L, 2L, 1L, 1L), delays,
            "the persistent, temporary, particle, and bounded viewer reconciliation drivers must re-arm");

        harness.stopTasks();
        harness.drainDelayed();
        assertTrue(harness.delayedTasks.isEmpty(), "stopped drivers must not re-arm");
        assertTrue(harness.schedulerErrors.isEmpty());
    }

    @Test
    void chunkLoadPurgeRemovesOnlyOrphanedGlossDisplays() {
        // A leased display owned by the service.
        TemporaryHologramDisplay temporary = harness.temporary("leased", harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(List.of("hi"));
        temporary.drive(true);
        DisplayHandle leased = harness.onlySpawned(world);
        world.chunkEntities.add(leased.proxy);

        DisplayHandle taggedOrphan = harness.orphanDisplay(world, true, false);
        DisplayHandle markedOrphan = harness.orphanDisplay(world, false, true);
        DisplayHandle foreignDisplay = harness.orphanDisplay(world, false, false);
        world.chunkEntities.add(harness.join("Bystander", world, 8.0D, 64.0D, 8.0D).proxy);

        harness.fireEntitiesLoad(world);
        harness.drainDelayed();

        assertTrue(taggedOrphan.removed, "scoreboard-tagged orphans must be purged");
        assertTrue(markedOrphan.removed, "PDC-marked orphans must be purged");
        assertFalse(foreignDisplay.removed, "foreign text displays must be preserved");
        assertFalse(leased.removed, "leased displays must survive the purge");
        assertTrue(harness.schedulerErrors.isEmpty());
    }

    @Test
    void startupChunkPurgeAdmitsOnlyOneBoundedBatchPerTick() {
        harness.loadChunks(world, 100);

        harness.service.sweepLoadedChunks();

        assertEquals(68, harness.service.startupChunkPurgeCount(),
            "the enable pass must schedule at most 32 loaded chunks immediately");
        harness.drainDelayed();
        assertEquals(36, harness.service.startupChunkPurgeCount(),
            "the repeating pump must admit one further bounded batch per tick");

        harness.stopTasks();
        assertEquals(0, harness.service.startupChunkPurgeCount(),
            "lifecycle cancellation must discard any startup purge backlog");
    }

    @Test
    void activeEntityCountTracksLeases() {
        assertEquals(0, harness.service.activeEntityCount());

        PersistentHologram hologram = harness.persistent("lease-holo", harness.at(world, 0.5D, 64.0D, 0.5D));
        hologram.setLines(List.of("hi"));
        hologram.update();
        TemporaryHologramDisplay temporary = harness.temporary("lease-temp", harness.at(world, 4.5D, 64.0D, 4.5D), 60_000L);
        temporary.setLines(List.of("tmp"));
        temporary.drive(true);
        assertEquals(2, harness.service.activeEntityCount());

        temporary.destroy();
        assertEquals(1, harness.service.activeEntityCount());

        hologram.despawnAll();
        assertEquals(0, harness.service.activeEntityCount());
    }

    @Test
    void deleteDespawnsDeregistersAndRemovesTheDocument() {
        PersistentHologram hologram = harness.persistent("doomed", harness.at(world, 0.5D, 64.0D, 0.5D));
        hologram.setLines(List.of("hi"));
        hologram.update();
        DisplayHandle display = harness.onlySpawned(world);
        CharacterizationHarness.awaitTrue("hologram document write", () -> docFile("doomed").isFile(), 5_000L);

        harness.service.delete("doomed");

        assertTrue(display.removed, "delete must despawn the display");
        assertFalse(harness.service.has("doomed"));
        assertEquals(0, harness.service.hologramCount());
        CharacterizationHarness.awaitTrue("hologram document delete", () -> !docFile("doomed").isFile(), 5_000L);
    }

    @Test
    void rapidMutationsCoalesceToOneQueuedLatestWrite() {
        PersistentHologram hologram = harness.persistent("coalesced", harness.at(world, 0.5D, 64.0D, 0.5D));
        hologram.setLines(List.of("initial"));
        CharacterizationHarness.awaitTrue("initial hologram write", () ->
            docFile("coalesced").isFile() && harness.service.pendingFileMutationCount() == 0, 5_000L);
        CountDownLatch release = harness.blockFileExecutor();
        try {
            long submissions = harness.service.fileDrainSubmissionCount();
            for (int mutation = 0; mutation < 100; mutation++) {
                hologram.setLine(0, "value-" + mutation);
            }

            assertEquals(1, harness.service.pendingFileMutationCount(),
                "rapid writes for one id must occupy one bounded queue slot");
            assertEquals(submissions + 1L, harness.service.fileDrainSubmissionCount(),
                "rapid writes for one id must schedule one drain");
        } finally {
            release.countDown();
        }

        CharacterizationHarness.awaitTrue("latest coalesced hologram write", () -> {
            try {
                return Files.readString(docFile("coalesced").toPath(), StandardCharsets.UTF_8)
                    .contains("value-99");
            } catch (java.io.IOException failure) {
                return false;
            }
        }, 5_000L);
    }

    @Test
    void deleteSupersedesQueuedAndStaleWrites() {
        PersistentHologram hologram = harness.persistent("delete-barrier",
            harness.at(world, 0.5D, 64.0D, 0.5D));
        hologram.setLines(List.of("initial"));
        CharacterizationHarness.awaitTrue("initial delete-barrier write", () ->
            docFile("delete-barrier").isFile() && harness.service.pendingFileMutationCount() == 0, 5_000L);
        CountDownLatch release = harness.blockFileExecutor();
        try {
            hologram.setLine(0, "queued-before-delete");
            harness.service.delete("delete-barrier");
            hologram.setLine(0, "stale-after-delete");
            assertEquals(1, harness.service.pendingFileMutationCount(),
                "delete must replace queued writes and reject stale object writes");
        } finally {
            release.countDown();
        }

        CharacterizationHarness.awaitTrue("delete barrier", () -> !docFile("delete-barrier").isFile(), 5_000L);
    }

    @Test
    void reloadRoundTripsPersistedHologramsAndRespawns() {
        PersistentHologram hologram = harness.persistent("survivor", harness.at(world, 0.5D, 64.0D, 0.5D));
        hologram.addLine("&aHello");
        CharacterizationHarness.awaitTrue("persisted lines on disk", () -> {
            File file = docFile("survivor");
            if (!file.isFile()) {
                return false;
            }
            try {
                return Files.readString(file.toPath(), StandardCharsets.UTF_8).contains("Hello");
            } catch (java.io.IOException failure) {
                return false;
            }
        }, 5_000L);

        hologram.update();
        DisplayHandle original = harness.onlySpawned(world);
        assertEquals("§aHello", original.lastText());

        harness.service.reload();
        harness.stopTasks();
        harness.drainDelayed();
        int liveBeforeRetiredUpdate = harness.liveSpawned(world).size();
        hologram.update();
        assertEquals(liveBeforeRetiredUpdate, harness.liveSpawned(world).size(),
            "a retired pre-reload instance must not add another display after the restored driver runs");
        harness.driveHolograms();

        assertTrue(original.removed, "reload must despawn the previous display");
        assertTrue(harness.service.has("survivor"), "reload must restore the hologram from disk");
        Hologram restored = harness.service.get("survivor");
        assertNotNull(restored);
        assertEquals(List.of("&aHello"), restored.lines(), "reload must round-trip the authored lines");

        DisplayHandle respawned = harness.onlySpawned(world);
        assertEquals("§aHello", respawned.lastText(), "reload must respawn with identical rendering");
        assertTrue(original != respawned);
    }

    @Test
    void reloadFlushesTheLatestQueuedDocumentBeforeLoading() {
        PersistentHologram hologram = harness.persistent("reload-latest",
            harness.at(world, 0.5D, 64.0D, 0.5D));
        for (int mutation = 0; mutation < 50; mutation++) {
            hologram.setLines(List.of("revision-" + mutation));
        }

        harness.service.reload();
        harness.stopTasks();
        harness.drainDelayed();

        assertEquals(List.of("revision-49"), harness.service.get("reload-latest").lines(),
            "reload must observe the last coalesced state, not an older file revision");
    }
}
