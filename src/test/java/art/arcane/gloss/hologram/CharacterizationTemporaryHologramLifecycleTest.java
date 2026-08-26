package art.arcane.gloss.hologram;

import art.arcane.gloss.hologram.CharacterizationHarness.DisplayHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.PlayerHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization pins for temporary-hologram text and lifecycle semantics (STEP 1, Char-1).
 *
 * <p>Frozen contract for Lane A (A6 interpolated motion, A7 purge, A10 spawn dirty-consume,
 * A11 function-token detection, A13 registry swap). Pins assert rendered text values, entity
 * lifecycle outcomes, and position bookkeeping — not render or dispatch counts — except where the
 * plan explicitly freezes the no-op ("unchanged lines produce no setText"). Same-world motion
 * deliberately pins only the epsilon no-op and the cross-world teleport, because A6 replaces
 * same-world teleports with client-side interpolation.</p>
 */
class CharacterizationTemporaryHologramLifecycleTest {
    @TempDir
    File dataFolder;

    private CharacterizationHarness harness;
    private WorldState world;
    private PlayerHandle owner;

    @BeforeEach
    void setUp() {
        harness = new CharacterizationHarness(dataFolder);
        world = harness.world("overworld");
        owner = harness.join("Alice", world, 1.0D, 64.0D, 1.0D);
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    private TemporaryHologramDisplay temporary(String id, List<String> lines) {
        TemporaryHologramDisplay temporary = harness.temporary(id, harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(lines);
        return temporary;
    }

    @Test
    void spawnRendersLinesThroughTheStaticPipeline() {
        TemporaryHologramDisplay temporary = temporary("t-spawn", List.of("&aHi", "Two"));
        temporary.drive(true);

        DisplayHandle display = harness.onlySpawned(world);
        assertEquals("§aHi§r\nTwo", display.lastText(),
            "spawn must render color codes and join lines with newlines");
        assertEquals(1, harness.service.temporaryCount());
        assertEquals(1, harness.service.activeEntityCount());
    }

    @Test
    void lineMutatorsApplyOnTheNextDrive() {
        TemporaryHologramDisplay temporary = temporary("t-mutate", List.of("&aHi", "Two"));
        temporary.drive(true);
        DisplayHandle display = harness.onlySpawned(world);

        temporary.setLine(0, "&bBye");
        temporary.drive(true);
        assertEquals("§bBye§r\nTwo", display.lastText());

        temporary.addLine("Three");
        temporary.drive(true);
        assertEquals("§bBye§r\nTwo§r\nThree", display.lastText());

        temporary.removeLine(1);
        temporary.drive(true);
        assertEquals("§bBye§r\nThree", display.lastText());

        temporary.clearLines();
        temporary.drive(true);
        assertEquals("", display.lastText(), "cleared lines must blank the display");
    }

    @Test
    void unchangedLinesProduceNoTextReassignment() {
        TemporaryHologramDisplay temporary = temporary("t-static", List.of("&aHi", "Two"));
        temporary.drive(true);
        DisplayHandle display = harness.onlySpawned(world);
        int assignments = display.textHistory.size();

        temporary.drive(true);
        temporary.drive(true);
        temporary.drive(true);

        assertEquals(assignments, display.textHistory.size(),
            "drives without line changes must not reassign text");
        assertFalse(temporary.hasPublishedAnimation(),
            "static drives must never enter the animator registry or trigger group scans");
        assertEquals(0, harness.animator.targetCount());
    }

    @Test
    void registeredFunctionLinesRefreshAtDriveCadence() {
        AtomicReference<String> value = new AtomicReference<>("one");
        harness.registerFunction("state", player -> value.get());
        TemporaryHologramDisplay temporary = temporary("t-fn", List.of("|state|"));

        temporary.drive(true);
        DisplayHandle display = harness.onlySpawned(world);
        assertEquals("one", display.lastText());

        value.set("two");
        temporary.drive(true);
        assertEquals("two", display.lastText(), "function output must refresh on the drive after it changes");

        value.set("three");
        temporary.drive(true);
        assertEquals("three", display.lastText());
    }

    @Test
    void animatedAudiencesShareOneOverBroadCellSnapshotPerDrive() {
        TemporaryHologramDisplay firstTemporary = temporary("t-audience-cache-a",
            List.of(CharacterizationHarness.FAST_CLIP_LINE));
        TemporaryHologramDisplay secondTemporary = temporary("t-audience-cache-b",
            List.of(CharacterizationHarness.FAST_CLIP_LINE));
        firstTemporary.drive(true);
        secondTemporary.drive(true);
        HologramTick tick = new HologramTick();

        firstTemporary.drive(tick, true);
        secondTemporary.drive(tick, true);

        assertEquals(1, tick.temporaryAudienceCellCount(),
            "clustered temporary effects must reuse one viewer-index query for their cell");
        Location firstLocation = firstTemporary.location();
        Location secondLocation = secondTemporary.location();
        assertSame(tick.temporaryPlayers(firstLocation.getWorld(), firstLocation, harness.service.viewRange()),
            tick.temporaryPlayers(secondLocation.getWorld(), secondLocation, harness.service.viewRange()),
            "an unfiltered audience must reuse the cell's immutable player list");
    }

    @Test
    void cellAudienceReuseStillAppliesExactRangeAtEachAnchor() {
        PlayerHandle boundary = harness.join("Boundary", world, 55.0D, 64.0D, 8.0D);
        Location left = harness.at(world, 0.5D, 64.0D, 0.5D);
        Location nearbyLeft = harness.at(world, 1.5D, 64.0D, 0.5D);
        Location right = harness.at(world, 15.5D, 64.0D, 0.5D);
        HologramTick tick = new HologramTick();

        List<Player> leftAudience = tick.temporaryPlayers(
            world.proxy, left, harness.service.viewRange());
        List<Player> nearbyLeftAudience = tick.temporaryPlayers(
            world.proxy, nearbyLeft, harness.service.viewRange());
        List<Player> rightAudience = tick.temporaryPlayers(
            world.proxy, right, harness.service.viewRange());

        assertEquals(List.of(owner.proxy), leftAudience);
        assertSame(leftAudience, nearbyLeftAudience,
            "anchors whose boundary set is empty must share the cell's immutable guaranteed list");
        assertTrue(rightAudience.contains(boundary.proxy),
            "an edge viewer must be included only where its exact distance is in range");
        assertEquals(1, tick.temporaryAudienceCellCount());
    }

    @Test
    void whitelistFallbackScansTheRosterOnlyOnReset() {
        world.visibleByDefaultSupported = false;
        PlayerHandle bob = harness.join("Bob", world, 2.0D, 64.0D, 2.0D);
        TemporaryHologramDisplay temporary = temporary("t-visibility-fallback", List.of("private"));
        temporary.viewers().whitelist();
        temporary.viewers().add(owner.uuid);

        temporary.drive(true);
        DisplayHandle display = harness.onlySpawned(world);
        int resetQueries = harness.onlinePlayerQueries.get();
        temporary.drive(true);
        temporary.drive(true);
        harness.reconcileViewers();
        harness.reconcileViewers();

        assertEquals(resetQueries, harness.onlinePlayerQueries.get(),
            "fallback whitelist drives and periodic viewer reconciliation must not rescan every player");
        assertFalse(bob.perceivedVisibility(display),
            "a failed visible-by-default setter must hide every nonmember during initial reconciliation");
    }

    @Test
    void unregisteredPipeTextKeepsItsValueWithoutChurn() {
        TemporaryHologramDisplay temporary = temporary("t-pipe", List.of("a | b"));
        temporary.drive(true);
        DisplayHandle display = harness.onlySpawned(world);
        int assignments = display.textHistory.size();

        temporary.drive(true);
        temporary.drive(true);

        assertEquals("a | b", display.lastText(), "unregistered pipe tokens must never be substituted");
        assertEquals(assignments, display.textHistory.size(),
            "identical re-renders must not reassign text");
    }

    @Test
    void zeroDurationExpiresOnTheFirstDrive() {
        TemporaryHologramDisplay temporary = harness.temporary("t-expire0", harness.at(world, 0.5D, 64.0D, 0.5D), 0L);
        temporary.setLines(List.of("hi"));
        temporary.drive(true);

        assertEquals(0, harness.service.temporaryCount(), "expired temporary must self-destroy");
        assertTrue(harness.liveSpawned(world).isEmpty(), "expired temporary must never leave an entity behind");
    }

    @Test
    void temporaryWaitsForAnEligibleNearbyViewerBeforeSpawning() {
        harness.moveTo(owner, world, 500.0D, 64.0D, 500.0D);
        TemporaryHologramDisplay temporary = temporary("t-audience-gate", List.of("private"));
        temporary.viewers().whitelist();
        temporary.viewers().add(owner.uuid);

        temporary.drive(true);
        assertTrue(harness.liveSpawned(world).isEmpty(),
            "a temporary with no exact eligible audience must not spawn");

        harness.moveTo(owner, world, 1.0D, 64.0D, 1.0D);
        temporary.drive(true);
        assertEquals(1, harness.liveSpawned(world).size(),
            "the same temporary must spawn when an eligible viewer enters range");
    }

    @Test
    void nullWorldIsRejectedBeforeTemporaryRegistration() {
        assertThrows(NullPointerException.class,
            () -> harness.service.createTemporary("t-null-world", new Location(null, 0.0D, 64.0D, 0.0D),
                60_000L));
        assertEquals(0, harness.service.temporaryCount());
    }

    @Test
    void expiryAfterSpawnDestroysTheEntity() {
        TemporaryHologramDisplay temporary = harness.temporary("t-expire", harness.at(world, 0.5D, 64.0D, 0.5D), 120L);
        temporary.setLines(List.of("hi"));
        temporary.drive(true);
        DisplayHandle display = harness.onlySpawned(world);

        CharacterizationHarness.awaitTrue("temporary expiry", () -> temporary.remainingMs() <= 0L, 5_000L);
        temporary.drive(true);

        assertTrue(display.removed, "expiry must remove the backing entity");
        assertEquals(0, harness.service.temporaryCount());
    }

    @Test
    void disabledDriveDespawnsRetractsAnimatorAndReenableRespawns() {
        TemporaryHologramDisplay temporary = temporary("t-toggle", List.of(CharacterizationHarness.FAST_CLIP_LINE));
        temporary.drive(true);
        temporary.drive(true);
        assertEquals(1, harness.animator.targetCount());
        DisplayHandle first = harness.onlySpawned(world);

        temporary.drive(false);
        assertTrue(first.removed, "disabled drive must despawn the display");
        assertEquals(0, harness.animator.targetCount(), "disabled drive must retract the animator group");
        assertEquals(1, harness.service.temporaryCount(), "disabling must not destroy the temporary");

        temporary.drive(true);
        DisplayHandle second = harness.onlySpawned(world);
        assertFalse(second.removed, "re-enable must respawn a fresh display");
        assertTrue(first != second);
    }

    @Test
    void positionBinderMovesTheHologramAndFailuresAreContained() {
        TemporaryHologramDisplay temporary = temporary("t-binder", List.of("hi"));
        temporary.drive(true);

        temporary.bindPosition(owner.proxy, () -> harness.at(world, 9.0D, 70.0D, 9.0D));
        harness.driveTemporary(temporary, true);
        assertEquals(9.0D, temporary.location().getX());
        assertEquals(70.0D, temporary.location().getY());

        temporary.bindPosition(owner.proxy, () -> {
            throw new IllegalStateException("binder boom");
        });
        harness.driveTemporary(temporary, true);
        assertEquals(9.0D, temporary.location().getX(), "failing binder must keep the last position");
        assertEquals(1, harness.service.temporaryCount(), "failing binder must not destroy the hologram");
    }

    @Test
    void crossWorldMoveTeleportsTheDisplay() {
        WorldState nether = harness.world("nether");
        TemporaryHologramDisplay temporary = temporary("t-cross", List.of("hi"));
        temporary.drive(true);
        DisplayHandle display = harness.onlySpawned(world);
        assertTrue(display.teleports.isEmpty());

        temporary.teleport(harness.at(nether, 3.0D, 70.0D, 3.0D));
        temporary.drive(true);

        assertEquals(1, display.teleports.size(), "world changes must remain real teleports");
        assertEquals(nether.proxy, display.teleports.get(0).getWorld());
        assertEquals(3.0D, display.teleports.get(0).getX());
    }

    @Test
    void stationaryDrivesNeverMoveTheDisplay() {
        TemporaryHologramDisplay temporary = temporary("t-still", List.of("hi"));
        temporary.drive(true);
        temporary.drive(true);
        temporary.drive(true);

        DisplayHandle display = harness.onlySpawned(world);
        assertTrue(display.teleports.isEmpty(), "an unmoved hologram must never dispatch movement");
    }

    @Test
    void unloadedChunkDefersSpawnUntilLoaded() {
        world.chunkLoaded = false;
        TemporaryHologramDisplay temporary = temporary("t-chunk", List.of("hi"));
        temporary.drive(true);
        assertTrue(harness.liveSpawned(world).isEmpty(), "unloaded chunks must defer the spawn");

        world.chunkLoaded = true;
        temporary.drive(true);
        assertEquals(1, harness.liveSpawned(world).size(), "spawn must proceed once the chunk loads");
    }
}
