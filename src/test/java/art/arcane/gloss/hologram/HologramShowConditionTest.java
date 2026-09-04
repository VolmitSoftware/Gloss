package art.arcane.gloss.hologram;

import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.hologram.CharacterizationHarness.DisplayHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.PlayerHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramShowConditionTest {
    @TempDir
    File directory;

    @Test
    void staticTextReevaluatesShowWithoutPlaceholderPersonalization() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            harness.configure(file -> file.holograms.perViewerPlaceholders = false);
            WorldState world = harness.world("world");
            PlayerHandle alice = harness.join("Alice", world, 0.0D, 64.0D, 0.0D);
            PlayerHandle bob = harness.join("Bob", world, 2.0D, 64.0D, 0.0D);
            PersistentHologram hologram = harness.persistent("show", harness.at(world, 0.0D, 64.0D, 0.0D));
            hologram.apply(document("player.x > 1"));
            hologram.update();
            harness.drainDelayed();
            DisplayHandle display = harness.onlySpawned(world);
            assertEquals(Boolean.FALSE, display.visibleByDefault);
            assertEquals(Boolean.FALSE, alice.perceivedVisibility(display));
            assertEquals(Boolean.TRUE, bob.perceivedVisibility(display));

            alice.location.setX(3.0D);
            bob.location.setX(0.0D);
            hologram.update();
            harness.drainDelayed();
            assertEquals(Boolean.TRUE, alice.perceivedVisibility(display));
            assertEquals(Boolean.FALSE, bob.perceivedVisibility(display));
            assertEquals(1, harness.liveSpawned(world).size());
            assertTrue(harness.schedulerErrors.isEmpty());
        }
    }

    @Test
    void falseHidesExistingEntitiesAndTrueRestoresThem() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            harness.join("Alice", world, 0.0D, 64.0D, 0.0D);
            PersistentHologram hologram = harness.persistent("show", harness.at(world, 0.0D, 64.0D, 0.0D));
            hologram.apply(document("false"));
            hologram.update();
            assertTrue(harness.liveSpawned(world).isEmpty());
            hologram.apply(document("true"));
            hologram.update();
            assertEquals(1, harness.liveSpawned(world).size());
            hologram.apply(document("false"));
            hologram.update();
            assertTrue(harness.liveSpawned(world).isEmpty());
        }
    }

    @Test
    void temporaryShowReevaluatesAndKeepsTheExistingBlacklist() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            PlayerHandle alice = harness.join("Alice", world, 0.0D, 64.0D, 0.0D);
            PlayerHandle bob = harness.join("Bob", world, 2.0D, 64.0D, 0.0D);
            TemporaryHologramDisplay hologram = harness.temporary("show", harness.at(world, 0.0D, 64.0D, 0.0D), 60000L);
            AtomicBoolean visible = new AtomicBoolean(false);
            hologram.setViewerCondition(viewer -> visible.get());
            hologram.viewers().add(bob.uuid);
            hologram.setLines(List.of("text"));
            hologram.drive(true);
            DisplayHandle display = harness.onlySpawned(world);
            assertEquals(Boolean.FALSE, alice.perceivedVisibility(display));
            visible.set(true);
            hologram.drive(true);
            assertEquals(Boolean.TRUE, alice.perceivedVisibility(display));
            assertEquals(Boolean.FALSE, bob.perceivedVisibility(display));
            visible.set(false);
            hologram.drive(true);
            assertEquals(Boolean.FALSE, alice.perceivedVisibility(display));
            assertTrue(harness.schedulerErrors.isEmpty());
        }
    }

    @Test
    void showSurvivesPersistenceAndRevisionChanges() {
        HologramDoc original = document("{{ world.time > 12000 }}");
        HologramDoc decoded = HologramDoc.parse("show.json", BukkitJson.GSON.toJson(original));
        assertEquals(original.show(), decoded.show());
        assertEquals(original.show(), decoded.withRevision(2L).show());
    }

    @Test
    void settledTemporaryFrameResendsAfterShowBecomesTrueAgain() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            harness.join("Alice", world, 0.0D, 64.0D, 0.0D);
            TemporaryHologramDisplay hologram = harness.temporary("frames", harness.at(world, 0.0D, 64.0D, 0.0D), 60000L);
            AtomicBoolean visible = new AtomicBoolean(true);
            hologram.setViewerCondition(viewer -> visible.get());
            hologram.setRenderedLines(List.of("base"));
            hologram.bindRenderedFrames(now -> List.of("settled"));
            hologram.drive(true);
            hologram.drive(true);
            assertEquals(1, harness.animator.pass(0L));
            visible.set(false);
            hologram.drive(true);
            assertEquals(0, harness.animator.pass(100L));
            visible.set(true);
            hologram.drive(true);
            assertEquals(1, harness.animator.pass(200L));
            assertEquals("settled", harness.sender.sent.getLast().text());
            assertEquals(2, harness.sender.sent.size());
        }
    }

    @Test
    void visibilityResultCannotShowADisplayReplacedDuringEvaluation() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            PlayerHandle alice = harness.join("Alice", world, 2.0D, 64.0D, 0.0D);
            PersistentHologram hologram = harness.persistent("reload", harness.at(world, 0.0D, 64.0D, 0.0D));
            hologram.apply(document("player.x > 1"));
            hologram.update();
            harness.drainDelayed();
            AtomicInteger reads = new AtomicInteger();
            alice.locationRead = () -> {
                if (reads.incrementAndGet() == 2) {
                    hologram.apply(document("player.x < 0"));
                    hologram.update();
                }
            };
            hologram.update();
            alice.locationRead = () -> {};
            DisplayHandle replacement = harness.liveSpawned(world).getFirst();
            assertEquals(2, world.spawned.size());
            assertEquals(0, alice.showCallsFor(replacement));
            harness.drainDelayed();
            assertEquals(Boolean.FALSE, alice.perceivedVisibility(replacement));
            assertTrue(harness.schedulerErrors.isEmpty());
        }
    }

    private static HologramDoc document(String expression) {
        HologramDoc base = HologramDoc.parse("show.json", """
            {"schemaVersion":2,"revision":1,"anchor":{"world":"world","position":[0,64,0]},
             "lines":["static"],"scale":1}
            """);
        return new HologramDoc(base.schemaVersion(), base.revision(), base.anchor(), base.lines(),
            base.seeThrough(), base.scale(), base.billboard(), base.yaw(), base.pitch(),
            base.particleLayers(), ShowCondition.of(expression));
    }
}
