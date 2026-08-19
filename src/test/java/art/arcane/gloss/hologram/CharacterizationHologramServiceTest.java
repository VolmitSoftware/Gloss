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
        assertEquals("§aA\nB", harness.service.renderStaticLines(List.of("&aA", "B")));
        assertEquals("solo", harness.service.renderStaticLines(List.of("solo")));
        assertEquals("", harness.service.renderStaticLines(List.of()));
        assertEquals("a\n\nb", harness.service.renderStaticLines(List.of("a", "", "b")),
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
        assertEquals(List.of(10L, 2L), delays,
            "the hologram driver and temporary driver must re-arm at their configured intervals");

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

        assertTrue(original.removed, "reload must despawn the previous display");
        assertTrue(harness.service.has("survivor"), "reload must restore the hologram from disk");
        Hologram restored = harness.service.get("survivor");
        assertNotNull(restored);
        assertEquals(List.of("&aHello"), restored.lines(), "reload must round-trip the authored lines");

        DisplayHandle respawned = harness.onlySpawned(world);
        assertEquals("§aHello", respawned.lastText(), "reload must respawn with identical rendering");
        assertTrue(original != respawned);
    }
}
