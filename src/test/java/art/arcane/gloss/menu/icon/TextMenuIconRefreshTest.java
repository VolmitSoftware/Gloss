package art.arcane.gloss.menu.icon;

import art.arcane.gloss.config.icon.TextIconData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextMenuIconRefreshTest {
    @Test
    void omittedCadenceAcceleratesOnlyClockDrivenText() {
        assertEquals(10, TextMenuIcon.refreshInterval(text(null), "{{ player.ping }}", true));
        assertEquals(1, TextMenuIcon.refreshInterval(text(null), "{{ time.ticks }}", true));
        assertEquals(1, TextMenuIcon.refreshInterval(text(null), "|animation.rainbow|", true));
        assertEquals(10, TextMenuIcon.refreshInterval(text(null), "{{ time.ticks }}", false));
    }

    @Test
    void explicitCadenceAlwaysWins() {
        assertEquals(0, TextMenuIcon.refreshInterval(text(0), "{{ time.ticks }}", true));
        assertEquals(7, TextMenuIcon.refreshInterval(text(7), "|animation.rainbow|", true));
    }

    private static TextIconData text(Integer refreshTicks) {
        return new TextIconData("", null, refreshTicks);
    }
}
