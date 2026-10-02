package art.arcane.gloss.animation.clip;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipPlanPingPongTest {
    @Test
    void reversesAtTheEndAndRepeatsWithoutStoppingUpdates() {
        Track track = new Track(null, Target.OFFSET_X, Blend.REPLACE,
            List.of(new Keyframe(0D, 0D, "", null), new Keyframe(10D, 1D, "", null)));
        Clip clip = new Clip(Trigger.SPAWN, 10D, LoopMode.PINGPONG, List.of(track));
        ClipPlan plan = ClipPlan.compile(new ClipSet(true, Map.of(),
            List.of(new Profile("motion", 0, List.of("*"), List.of(clip)))));

        assertEquals(0.5D, plan.sample("STONE", Trigger.SPAWN, 5D).offsetX());
        assertEquals(1D, plan.sample("STONE", Trigger.SPAWN, 10D).offsetX());
        assertEquals(0.5D, plan.sample("STONE", Trigger.SPAWN, 15D).offsetX());
        assertEquals(0D, plan.sample("STONE", Trigger.SPAWN, 20D).offsetX());
        assertEquals(0.5D, plan.sample("STONE", Trigger.SPAWN, 25D).offsetX());
        assertTrue(plan.requiresContinuousUpdates("STONE", Trigger.SPAWN, 10D));
        assertTrue(plan.requiresContinuousUpdates("STONE", Trigger.SPAWN, 15D));
    }
}
