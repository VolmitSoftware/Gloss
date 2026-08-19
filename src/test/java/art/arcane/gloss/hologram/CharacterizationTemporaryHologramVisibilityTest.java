package art.arcane.gloss.hologram;

import art.arcane.gloss.hologram.CharacterizationHarness.DisplayHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.PlayerHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization pins for temporary-hologram visibility semantics (STEP 1, Char-1).
 *
 * <p>Frozen contract for Lane A (A1 visibility-reconcile rewrite). Assertions pin OUTCOMES —
 * the effective per-player visibility, the applied-set entries that gate re-dispatch, and the
 * dispatch decisions that both the current and the planned reconcile shapes must make — never
 * roster-scan mechanics, so an implementation that stops enumerating the online roster still
 * passes.</p>
 */
class CharacterizationTemporaryHologramVisibilityTest {
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

    /** Effective visibility = explicit dispatch when present, else the display's spawn default. */
    private static boolean effectivelyVisible(PlayerHandle player, DisplayHandle display) {
        Boolean dispatched = player.perceivedVisibility(display);
        if (dispatched != null) {
            return dispatched;
        }

        return display.visibleByDefault == null || display.visibleByDefault;
    }

    private TemporaryHologramDisplay spawned(String id) {
        TemporaryHologramDisplay temporary = harness.temporary(id, harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(java.util.List.of("hi"));
        temporary.drive(true);
        return temporary;
    }

    @Test
    void blacklistWithoutMembersDispatchesNothingAndStaysVisible() {
        TemporaryHologramDisplay temporary = spawned("t-none");
        temporary.drive(true);
        temporary.drive(true);

        DisplayHandle display = harness.onlySpawned(world);
        for (PlayerHandle player : java.util.List.of(alice, bob, cara)) {
            assertNull(player.perceivedVisibility(display), player.name + " must receive no dispatch");
            assertTrue(effectivelyVisible(player, display));
        }
        assertNull(display.visibleByDefault, "blacklist spawn must not override default visibility");
        assertTrue(harness.appliedVisibility(temporary).isEmpty());
    }

    @Test
    void blacklistHidesExactlyMembersAndKeepsOthersVisible() {
        TemporaryHologramDisplay temporary = spawned("t-black");
        temporary.viewers().add(bob.uuid);
        temporary.drive(true);

        DisplayHandle display = harness.onlySpawned(world);
        assertFalse(effectivelyVisible(bob, display), "blacklisted member must end hidden");
        assertTrue(effectivelyVisible(alice, display));
        assertTrue(effectivelyVisible(cara, display));
        assertEquals(Boolean.FALSE, harness.appliedVisibility(temporary).get(bob.uuid),
            "applied set must record the member as hidden");
    }

    @Test
    void blacklistSteadyStateDoesNotRedispatchToMembers() {
        TemporaryHologramDisplay temporary = spawned("t-redispatch");
        temporary.viewers().add(bob.uuid);
        temporary.drive(true);

        DisplayHandle display = harness.onlySpawned(world);
        int hideCallsAfterFirstReconcile = bob.hideCallsFor(display);
        temporary.drive(true);
        temporary.drive(true);

        assertEquals(hideCallsAfterFirstReconcile, bob.hideCallsFor(display),
            "unchanged membership must not re-dispatch hide to the member");
        assertFalse(effectivelyVisible(bob, display));
    }

    @Test
    void blacklistMemberRemovalRestoresVisibility() {
        TemporaryHologramDisplay temporary = spawned("t-unhide");
        temporary.viewers().add(bob.uuid);
        temporary.drive(true);

        temporary.viewers().remove(bob.uuid);
        temporary.drive(true);

        DisplayHandle display = harness.onlySpawned(world);
        assertTrue(effectivelyVisible(bob, display), "former member must be visible again");
        assertTrue(effectivelyVisible(alice, display));
    }

    @Test
    void whitelistSpawnsHiddenByDefaultAndShowsOnlyMembers() {
        TemporaryHologramDisplay temporary = harness.temporary("t-white", harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(java.util.List.of("hi"));
        temporary.viewers().whitelist();
        temporary.viewers().add(alice.uuid);
        temporary.drive(true);
        temporary.drive(true);

        DisplayHandle display = harness.onlySpawned(world);
        assertEquals(Boolean.FALSE, display.visibleByDefault, "whitelist spawn must hide by default");
        assertTrue(effectivelyVisible(alice, display), "whitelist member must see the display");
        assertFalse(effectivelyVisible(bob, display));
        assertFalse(effectivelyVisible(cara, display));
    }

    @Test
    void whitelistMemberRemovalHidesFormerMember() {
        TemporaryHologramDisplay temporary = harness.temporary("t-white-rm", harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(java.util.List.of("hi"));
        temporary.viewers().whitelist();
        temporary.viewers().add(alice.uuid);
        temporary.drive(true);
        temporary.drive(true);

        temporary.viewers().remove(alice.uuid);
        temporary.drive(true);

        DisplayHandle display = harness.onlySpawned(world);
        assertFalse(effectivelyVisible(alice, display), "removed whitelist member must lose visibility");
        assertFalse(effectivelyVisible(bob, display));
    }

    @Test
    void modeFlipToBlacklistRestoresDefaultVisibilityAndReconciles() {
        TemporaryHologramDisplay temporary = harness.temporary("t-flip", harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(java.util.List.of("hi"));
        temporary.viewers().whitelist();
        temporary.viewers().add(alice.uuid);
        temporary.drive(true);
        temporary.drive(true);

        // Membership survives the mode flip: Alice is now a blacklist entry.
        temporary.viewers().blacklist();
        temporary.drive(true);

        DisplayHandle display = harness.onlySpawned(world);
        assertEquals(Boolean.TRUE, display.visibleByDefault,
            "flip to blacklist must restore visible-by-default");
        assertFalse(effectivelyVisible(alice, display), "surviving member is now blacklisted");
        assertTrue(effectivelyVisible(bob, display));
        assertTrue(effectivelyVisible(cara, display));
    }

    /**
     * Current shape prunes quit players from the applied set during the drive-time reconcile.
     * Lane A replaces the per-drive sweep with quit-event pruning: if this pin fails after A1,
     * the fix is to route the same outcome through the new quit hook, not to drop the outcome
     * (the applied set must not retain players who left).
     */
    @Test
    void quitScrubsAppliedVisibilityBookkeeping() {
        TemporaryHologramDisplay temporary = spawned("t-quit");
        temporary.viewers().add(bob.uuid);
        temporary.drive(true);

        harness.quit(cara);
        temporary.drive(true);

        Map<UUID, Boolean> applied = harness.appliedVisibility(temporary);
        assertFalse(applied.containsKey(cara.uuid), "applied set must not retain quit players");
        assertEquals(Boolean.FALSE, applied.get(bob.uuid), "member entry must survive the sweep");
    }

    @Test
    void destroyClearsEntityAnimatorGroupAndRegistration() {
        TemporaryHologramDisplay temporary = harness.temporary("t-destroy", harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(java.util.List.of(CharacterizationHarness.FAST_CLIP_LINE));
        temporary.drive(true);
        temporary.drive(true);
        assertEquals(1, harness.animator.targetCount(), "animated line must publish a target first");

        DisplayHandle display = harness.onlySpawned(world);
        temporary.destroy();

        assertEquals(0, harness.animator.targetCount(), "destroy must retract the animator group");
        assertTrue(display.removed, "destroy must remove the backing entity");
        assertEquals(0, harness.service.temporaryCount(), "destroy must deregister the temporary");
        assertTrue(harness.appliedVisibility(temporary).isEmpty(), "destroy must clear visibility bookkeeping");
        assertEquals(0, harness.service.activeEntityCount(), "destroy must release the entity lease");
    }

    @Test
    void animationTargetViewersHonorBlacklist() {
        TemporaryHologramDisplay temporary = harness.temporary("t-anim-black", harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(java.util.List.of(CharacterizationHarness.FAST_CLIP_LINE));
        temporary.viewers().add(bob.uuid);
        temporary.drive(true);
        temporary.drive(true);

        assertEquals(1, harness.animator.pass(0L));
        CharacterizationHarness.Sent sent = harness.sender.sent.get(0);
        assertTrue(sent.viewers().contains(alice.proxy));
        assertTrue(sent.viewers().contains(cara.proxy));
        assertFalse(sent.viewers().contains(bob.proxy), "blacklisted member must not receive animation packets");
    }

    @Test
    void animationTargetViewersHonorWhitelist() {
        TemporaryHologramDisplay temporary = harness.temporary("t-anim-white", harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(java.util.List.of(CharacterizationHarness.FAST_CLIP_LINE));
        temporary.viewers().whitelist();
        temporary.viewers().add(alice.uuid);
        temporary.drive(true);
        temporary.drive(true);

        assertEquals(1, harness.animator.pass(0L));
        CharacterizationHarness.Sent sent = harness.sender.sent.get(0);
        assertEquals(java.util.List.of(alice.proxy), sent.viewers(),
            "whitelist must capture exactly the members as animation viewers");
    }
}
