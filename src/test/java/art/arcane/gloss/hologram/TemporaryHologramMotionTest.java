package art.arcane.gloss.hologram;

import art.arcane.gloss.hologram.CharacterizationHarness.DisplayHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporaryHologramMotionTest {
    private static final String TELEPORT_DURATION_CALL = "setTeleportDuration";

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

    private TemporaryHologramDisplay temporary(String id) {
        TemporaryHologramDisplay temporary = harness.temporary(id, harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(List.of("hi"));
        return temporary;
    }

    @Test
    void interpolatedMotionArmsTheDisplayAtSpawn() {
        TemporaryHologramDisplay temporary = temporary("t-interp");
        temporary.drive(true);

        DisplayHandle display = harness.onlySpawned(world);
        assertTrue(display.callLog.contains(TELEPORT_DURATION_CALL),
            "an interpolated temporary must arm client-side motion when it spawns");
    }

    @Test
    void disablingTheKnobLeavesMotionUnarmed() {
        harness.configure(file -> file.holograms.interpolatedMotion = false);
        TemporaryHologramDisplay temporary = temporary("t-hard");
        temporary.drive(true);

        DisplayHandle display = harness.onlySpawned(world);
        assertFalse(display.callLog.contains(TELEPORT_DURATION_CALL),
            "the knob must leave hard teleports untouched");
    }

    @Test
    void flippingTheKnobRearmsTheLiveDisplayOnItsNextMove() {
        harness.configure(file -> file.holograms.interpolatedMotion = false);
        TemporaryHologramDisplay temporary = temporary("t-flip");
        temporary.drive(true);
        DisplayHandle display = harness.onlySpawned(world);
        assertFalse(display.callLog.contains(TELEPORT_DURATION_CALL));

        harness.configure(file -> file.holograms.interpolatedMotion = true);
        temporary.teleport(harness.at(world, 4.5D, 64.0D, 4.5D));
        temporary.drive(true);

        assertTrue(display.callLog.contains(TELEPORT_DURATION_CALL),
            "enabling the knob must arm displays that already exist");
        assertEquals(1, display.teleports.size(), "position still moves through a real teleport");
    }

    @Test
    void stationaryDrivesNeverTouchMotionState() {
        TemporaryHologramDisplay temporary = temporary("t-idle");
        temporary.drive(true);
        DisplayHandle display = harness.onlySpawned(world);
        int callsAfterSpawn = display.callLog.size();

        temporary.drive(true);
        temporary.drive(true);

        assertEquals(callsAfterSpawn, display.callLog.size(), "an unmoved hologram must not re-arm motion");
        assertTrue(display.teleports.isEmpty());
    }
}
