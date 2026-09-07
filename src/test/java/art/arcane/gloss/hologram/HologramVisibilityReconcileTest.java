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
    void aVisibilityResetReconcilesMembersInsteadOfTheWholeRoster() {
        TemporaryHologramDisplay temporary = harness.temporary("t-reset", harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(List.of("hi"));
        temporary.viewers().whitelist();
        temporary.viewers().add(alice.uuid);
        temporary.drive(true);
        temporary.drive(true);
        int rosterScans = harness.onlinePlayerQueries.get();

        temporary.viewers().blacklist();
        temporary.drive(true);

        DisplayHandle display = harness.onlySpawned(world);
        assertEquals(rosterScans, harness.onlinePlayerQueries.get(),
            "a visibility reset must not walk the online roster");
        assertEquals(Boolean.TRUE, display.visibleByDefault,
            "the blacklist default carries every non-member");
        assertEquals(Boolean.FALSE, alice.perceivedVisibility(display));
        assertNull(bob.perceivedVisibility(display), "non-members must not be dispatched to");
        assertNull(cara.perceivedVisibility(display));
        assertEquals(Map.of(alice.uuid, Boolean.FALSE), harness.appliedVisibility(temporary),
            "only the members belong in the applied set after a reset");
    }

    /**
     * Flipping the default visibility inverts every per-player override that is already in place,
     * so a reset must re-dispatch to the players who carry one even when the recorded value matches.
     */
    @Test
    void aVisibilityResetRedispatchesToPlayersCarryingAnOverride() {
        TemporaryHologramDisplay temporary = spawned("t-invert");
        temporary.viewers().add(bob.uuid);
        temporary.drive(true);
        DisplayHandle display = harness.onlySpawned(world);
        int hides = bob.hideCallsFor(display);

        temporary.viewers().whitelist();
        temporary.viewers().remove(bob.uuid);
        temporary.viewers().add(alice.uuid);
        temporary.drive(true);

        assertEquals(Boolean.FALSE, display.visibleByDefault);
        assertTrue(bob.hideCallsFor(display) > hides,
            "the former member's override must be re-dispatched under the new default");
        assertFalse(harness.appliedVisibility(temporary).containsKey(bob.uuid));
        assertEquals(Boolean.TRUE, alice.perceivedVisibility(display));
    }

    @Test
    void aWhitelistResetShowsEveryMemberEvenWhenItWasAlreadyShown() {
        TemporaryHologramDisplay temporary = harness.temporary("t-white-reset",
            harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(List.of("hi"));
        temporary.viewers().whitelist();
        temporary.viewers().add(alice.uuid);
        temporary.drive(true);
        temporary.drive(true);
        DisplayHandle display = harness.onlySpawned(world);
        int shows = alice.showCallsFor(display);

        temporary.viewers().blacklist();
        temporary.viewers().remove(alice.uuid);
        temporary.viewers().whitelist();
        temporary.viewers().add(alice.uuid);
        temporary.drive(true);

        assertEquals(Boolean.FALSE, display.visibleByDefault);
        assertTrue(alice.showCallsFor(display) > shows,
            "a member must be re-shown after the default is re-applied");
        assertNull(bob.perceivedVisibility(display));
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
