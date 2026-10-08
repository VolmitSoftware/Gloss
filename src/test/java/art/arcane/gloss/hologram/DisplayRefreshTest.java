package art.arcane.gloss.hologram;

import art.arcane.gloss.bubble.BubbleStyleDoc;
import art.arcane.gloss.indicator.DamageIndicatorSettingsDoc;
import art.arcane.gloss.drop.RealDropSettingsDoc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisplayRefreshTest {
    @TempDir
    File folder;

    @Test
    void clocksRemainIndependentAndSkipMissedPeriodsWithoutCatchingUp() {
        DisplayRefreshClock content = new DisplayRefreshClock();
        DisplayRefreshClock motion = new DisplayRefreshClock();
        assertTrue(content.due(100L, 10));
        assertTrue(motion.due(100L, 2));
        assertFalse(content.due(102L, 10));
        assertTrue(motion.due(102L, 2));
        assertTrue(content.due(121L, 10));
        assertFalse(content.due(122L, 10));
        content.reset();
        assertTrue(content.due(122L, 10));
        assertTrue(content.due(123L, null));
        assertThrows(IllegalArgumentException.class, () -> new DisplayRefresh(0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new DisplayRefresh(null, 1201, null));
    }

    @Test
    void documentsPreserveIndependentRefreshPolicies() {
        HologramDoc hologram = HologramDoc.parse("hologram.json", """
            {"schemaVersion":3,"revision":1,"anchor":{"world":"world","position":[0,64,0]},
             "lines":["Hello"],"refresh":{"contentTicks":20,"visibilityTicks":5,"motionTicks":2}}
            """);
        assertEquals(new DisplayRefresh(20, 5, 2), hologram.withRevision(2).refresh());
        BubbleStyleDoc bubble = BubbleStyleDoc.parse("bubble.json", """
            {"schemaVersion":5,"revision":1,"refresh":{"motionTicks":4}}
            """);
        assertEquals(4, bubble.refresh().motionTicks());
        DamageIndicatorSettingsDoc indicator = DamageIndicatorSettingsDoc.parse("default.json", """
            {"schemaVersion":4,"revision":1,"damage":{"presentation":{"refresh":{"contentTicks":8}}}}
            """);
        assertEquals(8, indicator.damage().presentation().refresh().contentTicks());
        RealDropSettingsDoc drops = RealDropSettingsDoc.parse("default.json", """
            {"schemaVersion":4,"revision":1,"presentation":{"labels":{"refresh":{"visibilityTicks":6}}}}
            """);
        assertEquals(6, drops.presentation().labels().refresh().visibilityTicks());
    }

    @Test
    void persistentDriverHonorsMotionIntervalsBelowTextCadence() {
        try (CharacterizationHarness harness = new CharacterizationHarness(folder)) {
            CharacterizationHarness.WorldState world = harness.world("world");
            PersistentHologram hologram = harness.persistent("cadence", harness.at(world, 0, 65, 2));
            hologram.apply(HologramDoc.parse("cadence.json", """
                {"schemaVersion":3,"revision":1,"anchor":{"world":"world","position":[0,65,2]},
                 "lines":["Hello"],"refreshTicks":10,"refresh":{"motionTicks":2}}
                """));
            assertEquals(2, hologram.refreshTicks());
            assertTrue(hologram.refreshDue(100));
            assertFalse(hologram.refreshDue(101));
            assertTrue(hologram.refreshDue(102));
        }
    }

    @Test
    void temporaryMotionSamplingCanSlowWithoutDelayingExplicitTextEdits() {
        try (CharacterizationHarness harness = new CharacterizationHarness(folder)) {
            CharacterizationHarness.WorldState world = harness.world("world");
            CharacterizationHarness.PlayerHandle viewer = harness.join("Viewer", world, 0, 64, 0);
            TemporaryHologramDisplay temporary = harness.temporary("cadence", harness.at(world, 0, 65, 2), Long.MAX_VALUE);
            temporary.setRefresh(new DisplayRefresh(1200, 1200, 1200));
            temporary.setLines(List.of("First"));
            AtomicInteger samples = new AtomicInteger();
            temporary.bindPosition(viewer.proxy, () -> {
                samples.incrementAndGet();
                return harness.at(world, 0, 65, 2);
            });
            harness.driveTemporary(temporary, true);
            harness.driveTemporary(temporary, true);
            temporary.setLines(List.of("Second"));
            harness.driveTemporary(temporary, true);
            assertEquals(1, samples.get());
            assertEquals("Second", harness.onlySpawned(world).text);
            assertTrue(harness.schedulerErrors.isEmpty(), harness.schedulerErrors.toString());
        }
    }
}
