package art.arcane.gloss.panel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VisibleBoardCounterTest {
    @Test
    void currentEpochTracksOpenAndCloseTransitions() {
        PanelRuntimeManager.VisibleBoardCounter counter = new PanelRuntimeManager.VisibleBoardCounter();
        long epoch = counter.epoch();

        counter.add(epoch, 2);
        counter.add(epoch, -1);

        assertEquals(1, counter.get());
    }

    @Test
    void shutdownInvalidatesDelayedCloseAndOpenAccounting() {
        PanelRuntimeManager.VisibleBoardCounter counter = new PanelRuntimeManager.VisibleBoardCounter();
        long staleEpoch = counter.epoch();
        counter.add(staleEpoch, 3);

        counter.closeEpoch();
        counter.add(staleEpoch, -3);
        counter.add(staleEpoch, 1);

        assertEquals(0, counter.get());
        assertEquals(-1L, counter.epoch());
    }

    @Test
    void duplicateCloseCannotMakeTheCountNegative() {
        PanelRuntimeManager.VisibleBoardCounter counter = new PanelRuntimeManager.VisibleBoardCounter();
        long epoch = counter.epoch();

        counter.add(epoch, 1);
        counter.add(epoch, -1);
        counter.add(epoch, -1);

        assertEquals(0, counter.get());
    }
}
