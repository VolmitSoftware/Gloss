package art.arcane.gloss.forge;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlyphServiceRebuildTest {
    private final AtomicLong clock = new AtomicLong();
    private final GlyphService.RebuildGate gate =
        new GlyphService.RebuildGate(clock::get, TimeUnit.SECONDS.toNanos(GlyphService.DEBOUNCE_SECONDS));

    private void advanceSeconds(long seconds) {
        clock.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }

    @Test
    void theFirstFingerprintArmsARebuild() {
        assertTrue(gate.observe("a"));
        assertFalse(gate.due());

        advanceSeconds(GlyphService.DEBOUNCE_SECONDS);

        assertTrue(gate.due());
        assertEquals("a", gate.built());
    }

    @Test
    void anUnchangedFingerprintNeverRebuilds() {
        gate.observe("a");
        advanceSeconds(GlyphService.DEBOUNCE_SECONDS);
        gate.due();

        assertFalse(gate.observe("a"));
        advanceSeconds(60L);
        assertFalse(gate.due());
    }

    @Test
    void editsInsideTheWindowCollapseIntoOneRebuild() {
        gate.observe("a");
        advanceSeconds(GlyphService.DEBOUNCE_SECONDS);
        gate.due();

        gate.observe("b");
        advanceSeconds(2L);
        assertFalse(gate.due());
        gate.observe("c");
        advanceSeconds(2L);
        assertFalse(gate.due());

        advanceSeconds(GlyphService.DEBOUNCE_SECONDS);

        assertTrue(gate.due());
        assertEquals("c", gate.built());
        assertFalse(gate.due());
    }

    @Test
    void revertingAnEditInsideTheWindowCancelsTheRebuild() {
        gate.observe("a");
        advanceSeconds(GlyphService.DEBOUNCE_SECONDS);
        gate.due();

        assertTrue(gate.observe("b"));
        assertFalse(gate.observe("a"));
        advanceSeconds(60L);

        assertFalse(gate.due());
        assertEquals("a", gate.built());
    }

    @Test
    void aFailedRebuildIsRetriedOnTheNextChange() {
        gate.observe("a");
        advanceSeconds(GlyphService.DEBOUNCE_SECONDS);
        gate.due();
        gate.failed();

        assertTrue(gate.observe("a"));
        advanceSeconds(GlyphService.DEBOUNCE_SECONDS);
        assertTrue(gate.due());
    }
}
