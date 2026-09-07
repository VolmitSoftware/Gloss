package art.arcane.gloss.particle;

import art.arcane.gloss.particle.ParticleService.Budget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParticleBudgetTest {
    @Test
    void aViewerNeverEmitsMoreThanItsOwnQuota() {
        Budget global = new Budget();
        Budget viewer = new Budget();

        assertEquals(8, ParticleService.admit(global, viewer, 1L, 32, 4096, 8));
    }

    /**
     * The global pool used to lose whatever the per viewer quota clipped, so one saturated viewer
     * drained the server wide budget for everyone else.
     */
    @Test
    void theGlobalPoolOnlyLosesWhatIsActuallyEmitted() {
        Budget global = new Budget();
        Budget clipped = new Budget();
        Budget other = new Budget();

        assertEquals(8, ParticleService.admit(global, clipped, 1L, 100, 100, 8));
        assertEquals(92, ParticleService.admit(global, other, 1L, 100, 100, 1000),
            "the clipped remainder must stay available to other viewers");
    }

    @Test
    void aSaturatedViewerDoesNotDrainTheGlobalPoolAtAll() {
        Budget global = new Budget();
        Budget saturated = new Budget();
        Budget other = new Budget();

        assertEquals(4, ParticleService.admit(global, saturated, 1L, 64, 64, 4));
        assertEquals(0, ParticleService.admit(global, saturated, 1L, 64, 64, 4));
        assertEquals(60, ParticleService.admit(global, other, 1L, 64, 64, 64));
    }

    @Test
    void theGlobalCeilingStillCapsTheServer() {
        Budget global = new Budget();

        assertEquals(10, ParticleService.admit(global, new Budget(), 1L, 10, 10, 100));
        assertEquals(0, ParticleService.admit(global, new Budget(), 1L, 10, 10, 100));
    }

    @Test
    void aNewTickReopensBothPools() {
        Budget global = new Budget();
        Budget viewer = new Budget();

        assertEquals(8, ParticleService.admit(global, viewer, 1L, 32, 8, 8));
        assertEquals(0, ParticleService.admit(global, viewer, 1L, 32, 8, 8));
        assertEquals(8, ParticleService.admit(global, viewer, 2L, 32, 8, 8));
    }

    @Test
    void aZeroRequestReservesNothing() {
        Budget global = new Budget();
        Budget viewer = new Budget();

        assertEquals(0, ParticleService.admit(global, viewer, 1L, 0, 4096, 128));
        assertEquals(4096, ParticleService.admit(global, viewer, 1L, 5000, 4096, 10000));
    }
}
