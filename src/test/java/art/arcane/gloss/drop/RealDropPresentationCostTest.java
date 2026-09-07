package art.arcane.gloss.drop;

import art.arcane.gloss.api.ParticleLayer;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-tick decisions that used to redo work every poll: the audience hide/show dispatch, the
 * item-stack comparison, the particle poll clamp, and the teardown of a presentation whose displays
 * were removed under it.
 */
class RealDropPresentationCostTest {

    @Test
    void stackIdentityIsTypeAndAmountWithNoMetaComparison() {
        assertFalse(RealDropService.stackChanged(Material.STONE, 4, Material.STONE, 4));
        assertTrue(RealDropService.stackChanged(Material.STONE, 4, Material.STONE, 5));
        assertTrue(RealDropService.stackChanged(Material.STONE, 4, Material.DIRT, 4));
        assertTrue(RealDropService.stackChanged(null, 0, Material.STONE, 1));
    }

    @Test
    void aSettledDropKeepsTheConfiguredLayerEmissionRate() {
        assertEquals(4, RealDropService.particlePollDelay(List.of(layer(4)), 20, true));
        assertEquals(1, RealDropService.particlePollDelay(List.of(layer(1), layer(4)), 20, true));
        assertEquals(2, RealDropService.particlePollDelay(List.of(layer(4)), 2, true));
        assertEquals(20, RealDropService.particlePollDelay(List.of(layer(4)), 20, false));
        assertEquals(20, RealDropService.particlePollDelay(List.of(), 20, true));
    }

    @Test
    void theFastParticlePollsSkipTheSettledStateWorkUntilTheSettledCadenceElapses() {
        assertTrue(RealDropService.particleOnlyPoll(20, 4));
        assertTrue(RealDropService.particleOnlyPoll(5, 4));
        assertFalse(RealDropService.particleOnlyPoll(4, 4));
        assertFalse(RealDropService.particleOnlyPoll(0, 4));
        assertFalse(RealDropService.particleOnlyPoll(-1, 4));
    }

    @Test
    void aPresentationWithNoLiveDisplayIsTornDownAfterTwoPolls() {
        List<Display> dead = List.of(DropFakes.display(false), DropFakes.display(false));
        List<Display> alive = List.of(DropFakes.display(false), DropFakes.display(true));

        assertEquals(0, RealDropService.missingVisualStrikes(alive, 1));
        int first = RealDropService.missingVisualStrikes(dead, 0);
        assertEquals(1, first);
        assertTrue(first < RealDropService.MISSING_VISUAL_TEARDOWN_TICKS);
        assertEquals(RealDropService.MISSING_VISUAL_TEARDOWN_TICKS,
            RealDropService.missingVisualStrikes(dead, first));
        assertEquals(0, RealDropService.missingVisualStrikes(List.of(), 0) - 1);
    }

    private static ParticleLayer layer(int intervalTicks) {
        return new ParticleLayer("layer-" + intervalTicks,
            new ParticleLayer.Target("model", null, null, null),
            new ParticleLayer.Geometry("point", null, null, null, null, null, null, null, null),
            null,
            new ParticleLayer.ParticleSpec("minecraft:flame", null, null),
            new ParticleLayer.Emission(intervalTicks, null, null, null),
            0);
    }
}
