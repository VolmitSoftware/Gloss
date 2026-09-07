package art.arcane.gloss.board;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The board selection sweep used to re-select every online player inside one tick, once per
 * interval. It now walks a slice per tick; this pins the slice arithmetic: a cycle always closes
 * inside its interval and no tick takes more than one extra player over the even share.
 */
class BoardSweepStripeTest {
    @Test
    void aOneTickIntervalSweepsTheWholeFleetWithoutBuildingACycle() {
        assertTrue(BoardService.sweepsWholeFleet(1));
        assertTrue(BoardService.sweepsWholeFleet(0));
        assertFalse(BoardService.sweepsWholeFleet(2));
        assertFalse(BoardService.sweepsWholeFleet(20));
    }

    @Test
    void anEmptyFleetCostsNothing() {
        assertEquals(0, BoardService.stripeSize(0, 20));
        assertEquals(0, BoardService.stripeSize(-1, 20));
    }

    @Test
    void theLastStripeTakesEverythingThatIsLeft() {
        assertEquals(7, BoardService.stripeSize(7, 1));
        assertEquals(7, BoardService.stripeSize(7, 0));
    }

    @Test
    void aFullCycleCoversEveryPlayerExactlyOnceAndStaysBalanced() {
        for (int players = 0; players <= 64; players++) {
            for (int interval = 1; interval <= 20; interval++) {
                int cursor = 0;
                int largestSlice = 0;
                for (int stripe = 0; stripe < interval; stripe++) {
                    int slice = BoardService.stripeSize(players - cursor, interval - stripe);
                    largestSlice = Math.max(largestSlice, slice);
                    cursor += slice;
                }
                assertEquals(players, cursor, "players=" + players + " interval=" + interval);
                assertTrue(largestSlice <= (players + interval - 1) / interval,
                    "players=" + players + " interval=" + interval + " largest=" + largestSlice);
            }
        }
    }

    @Test
    void oneThousandPlayersOnTheDefaultIntervalNeverBurstMoreThanFiftyPerTick() {
        assertEquals(50, BoardService.stripeSize(1000, 20));
    }
}
