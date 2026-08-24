package art.arcane.gloss.hologram;

import art.arcane.gloss.hologram.CharacterizationHarness.PlayerHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramViewerReconciliationTest {
    @TempDir
    File dataFolder;

    private CharacterizationHarness harness;
    private WorldState world;
    private PlayerHandle player;

    @BeforeEach
    void setUp() {
        harness = new CharacterizationHarness(dataFolder);
        world = harness.world("overworld");
        player = harness.join("Rider", world, 200.0D, 64.0D, 200.0D);
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void respawnPublishesTheEventDestinationImmediately() {
        Location respawn = harness.at(world, 1.0D, 70.0D, 1.0D);

        harness.fireRespawn(player, respawn);

        assertEquals(player.uuid, harness.indexedViewers(respawn, 1.0D).getFirst().id());
    }

    @Test
    void boundedReconciliationFindsPassengerMovementWithoutMoveEvent() {
        Location destination = harness.at(world, 1.0D, 64.0D, 1.0D);
        player.location = destination;
        assertTrue(harness.indexedViewers(destination, 1.0D).isEmpty());

        harness.reconcileViewers();

        assertEquals(player.uuid, harness.indexedViewers(destination, 1.0D).getFirst().id());
    }

    @Test
    void unchangedReconciliationDoesNotInvalidatePersonalizedTracking() {
        player.location = harness.at(world, 1.0D, 64.0D, 1.0D);
        harness.reconcileViewers();
        PersistentHologram hologram = harness.persistent("h-reconcile",
            harness.at(world, 0.5D, 64.0D, 0.5D));
        hologram.setLines(List.of("Hello %player_name%"));
        hologram.update();
        harness.drainDelayed();
        harness.animator.pass(System.currentTimeMillis());
        int initialPackets = harness.sender.sent.size();

        harness.reconcileViewers();
        hologram.update();
        harness.animator.pass(System.currentTimeMillis());

        assertEquals(initialPackets, harness.sender.sent.size(),
            "an unchanged periodic sample must not resend personalized metadata");

        player.location = harness.at(world, 17.0D, 64.0D, 1.0D);
        harness.reconcileViewers();
        hologram.update();
        harness.animator.pass(System.currentTimeMillis());

        assertEquals(initialPackets + 1, harness.sender.sent.size());
    }
}
