package art.arcane.gloss.surface;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The surface driver sweeps the way the sidebar driver does, so a thousand viewers cost the same
 * shape of work; these assertions pin the slice arithmetic against the same values.
 */
class SurfaceDriverTest {
    @Test
    void aOneTickIntervalSweepsTheWholeFleetWithoutBuildingACycle() {
        assertTrue(SurfaceDriver.sweepsWholeFleet(1));
        assertTrue(SurfaceDriver.sweepsWholeFleet(0));
        assertFalse(SurfaceDriver.sweepsWholeFleet(2));
        assertFalse(SurfaceDriver.sweepsWholeFleet(20));
    }

    @Test
    void anEmptyFleetCostsNothing() {
        assertEquals(0, SurfaceDriver.stripeSize(0, 20));
        assertEquals(0, SurfaceDriver.stripeSize(-1, 20));
    }

    @Test
    void theLastStripeTakesEverythingThatIsLeft() {
        assertEquals(7, SurfaceDriver.stripeSize(7, 1));
        assertEquals(7, SurfaceDriver.stripeSize(7, 0));
    }

    @Test
    void anEvenShareIsRoundedUpSoTheCycleNeverStalls() {
        assertEquals(2, SurfaceDriver.stripeSize(10, 5));
        assertEquals(2, SurfaceDriver.stripeSize(7, 4));
        assertEquals(100, SurfaceDriver.stripeSize(1000, 10));
    }

    @Test
    void aFullCycleCoversEveryViewerExactlyOnceAndStaysBalanced() {
        for (int viewers = 0; viewers <= 64; viewers++) {
            for (int interval = 1; interval <= 20; interval++) {
                int cursor = 0;
                int largestSlice = 0;
                for (int stripe = 0; stripe < interval; stripe++) {
                    int slice = SurfaceDriver.stripeSize(viewers - cursor, interval - stripe);
                    largestSlice = Math.max(largestSlice, slice);
                    cursor += slice;
                }
                assertEquals(viewers, cursor, "viewers=" + viewers + " interval=" + interval);
                assertTrue(largestSlice <= (viewers + interval - 1) / interval,
                    "viewers=" + viewers + " interval=" + interval + " largest=" + largestSlice);
            }
        }
    }
}
