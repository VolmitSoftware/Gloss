package art.arcane.gloss.hologram;

import art.arcane.gloss.hologram.CharacterizationHarness.DisplayHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.PlayerHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramVisibilityReconcileTest {
    @TempDir
    File dataFolder;

    private CharacterizationHarness harness;
    private WorldState world;
    private PlayerHandle alice;
    private PlayerHandle bob;
    private PlayerHandle cara;

    @BeforeEach
    void setUp() {
        harness = new CharacterizationHarness(dataFolder);
        world = harness.world("overworld");
        alice = harness.join("Alice", world, 1.0D, 64.0D, 1.0D);
        bob = harness.join("Bob", world, 2.0D, 64.0D, 2.0D);
        cara = harness.join("Cara", world, 3.0D, 64.0D, 3.0D);
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    private TemporaryHologramDisplay spawned(String id) {
        TemporaryHologramDisplay temporary = harness.temporary(id, harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(List.of("hi"));
        temporary.drive(true);
        return temporary;
    }

    @Test
    void blacklistBookkeepingIsBoundedToTheMembers() {
        TemporaryHologramDisplay temporary = spawned("t-bounded");
        temporary.viewers().add(bob.uuid);
        temporary.drive(true);
        temporary.drive(true);

        DisplayHandle display = harness.onlySpawned(world);
        Map<UUID, Boolean> applied = harness.appliedVisibility(temporary);
        assertEquals(1, applied.size(), "only blacklisted members belong in the applied set");
        assertEquals(Boolean.FALSE, applied.get(bob.uuid));
        assertNull(alice.perceivedVisibility(display), "non-members must never receive a visibility dispatch");
        assertNull(cara.perceivedVisibility(display));
    }

    @Test
    void blacklistRemovalUnhidesAndDropsTheAppliedEntry() {
        TemporaryHologramDisplay temporary = spawned("t-drop");
        temporary.viewers().add(bob.uuid);
        temporary.drive(true);

        temporary.viewers().remove(bob.uuid);
        temporary.drive(true);

        DisplayHandle display = harness.onlySpawned(world);
        assertEquals(Boolean.TRUE, bob.perceivedVisibility(display), "the former member must be shown again");
        assertTrue(harness.appliedVisibility(temporary).isEmpty(), "the applied set must not retain former members");
    }

    @Test
    void visibilityResetStillReconcilesTheWholeRoster() {
        TemporaryHologramDisplay temporary = harness.temporary("t-reset", harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(List.of("hi"));
        temporary.viewers().whitelist();
        temporary.viewers().add(alice.uuid);
        temporary.drive(true);
        temporary.drive(true);

        temporary.viewers().blacklist();
        temporary.drive(true);

        DisplayHandle display = harness.onlySpawned(world);
        assertEquals(Boolean.FALSE, alice.perceivedVisibility(display));
        assertEquals(Boolean.TRUE, bob.perceivedVisibility(display));
        assertEquals(Boolean.TRUE, cara.perceivedVisibility(display));
        assertEquals(3, harness.appliedVisibility(temporary).size(),
            "a visibility reset reconciles every online player");
    }

    @Test
    void quitPrunesWhitelistBookkeepingWithoutADrive() {
        TemporaryHologramDisplay temporary = harness.temporary("t-quit-white", harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(List.of("hi"));
        temporary.viewers().whitelist();
        temporary.viewers().add(alice.uuid);
        temporary.drive(true);
        temporary.drive(true);
        assertEquals(Boolean.TRUE, harness.appliedVisibility(temporary).get(alice.uuid));

        harness.quit(alice);

        assertFalse(harness.appliedVisibility(temporary).containsKey(alice.uuid),
            "the quit hook must drop bookkeeping for players who left");
    }

    @Test
    void quitReleasesPersonalizedStateWithoutDuplicatingTheEntity() {
        harness.registerFunction("who", player -> player == null ? "console" : player.getName());
        PersistentHologram hologram = harness.persistent("h-quit", harness.at(world, 0.5D, 64.0D, 0.5D));
        hologram.setLines(List.of("%p% |who|"));
        hologram.update();
        harness.drainDelayed();
        assertEquals(1, harness.liveSpawned(world).size());
        assertEquals(3, hologram.activeViewerCount());

        harness.quit(cara);

        assertEquals(1, harness.liveSpawned(world).size(),
            "personalized viewers must share one server entity");
        assertEquals(2, hologram.activeViewerCount(),
            "a quitting viewer must release its personalized packet state immediately");
        assertEquals(1, harness.service.activeEntityCount());
    }
}
