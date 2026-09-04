package art.arcane.gloss.tab;

import art.arcane.gloss.condition.ShowCondition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TablistRefreshIntervalTest {
    @Test
    void staticPlaceholderAndOrdinaryExpressionDocumentsKeepConfiguredCadence() {
        assertEquals(40, TablistService.refreshIntervalTicks(doc(true, "&aHeader", "&7Footer", List.of()), 40));
        assertEquals(40, TablistService.refreshIntervalTicks(
            doc(true, "%player_name%", "%server_online%", List.of()), 40));
        assertEquals(40, TablistService.refreshIntervalTicks(
            doc(true, "{{ player.ping }}", "{{ fixed(server.tps, 1) }}", List.of()), 40));
    }

    @Test
    void animatedBaseOrVariantUsesFastCadence() {
        TablistDoc base = doc(true, "{{ wave('GLOSS', ['&a', '&b'], time.ticks) }}", "", List.of());
        TablistDoc variant = doc(true, "", "", List.of(new TablistDoc.HeaderFooterVariant(
            "animated", 1, "true",
            new TablistDoc.HeaderFooterPresentation("", "|animation.rainbow|"))));

        assertEquals(1, TablistService.refreshIntervalTicks(base, 40));
        assertEquals(1, TablistService.refreshIntervalTicks(variant, 40));
        assertEquals(1, TablistService.refreshIntervalTicks(base, 1));
    }

    @Test
    void disabledHeaderFooterDoesNotAccelerateTheDriver() {
        TablistDoc disabled = doc(false, "{{ time.ticks }}", "|animation.rainbow|", List.of());

        assertEquals(40, TablistService.refreshIntervalTicks(disabled, 40));
    }

    @Test
    void fastOverridesAndNamesUseTheSeparateDriverOnlyWhenNeeded() {
        TablistDoc enabled = doc(true, "", "", List.of());
        TablistDoc disabled = doc(false, "", "", List.of());

        assertTrue(TablistService.fastDriverRequired(enabled, true, true, false, 40));
        assertFalse(TablistService.fastDriverRequired(disabled, true, true, false, 40));
        assertFalse(TablistService.fastDriverRequired(enabled, false, true, false, 40));
        assertFalse(TablistService.fastDriverRequired(enabled, true, false, false, 40));
        assertFalse(TablistService.fastDriverRequired(enabled, true, true, false, 1));

        TablistDoc names = new TablistDoc(2, 1L, ShowCondition.ALWAYS, disabled.headerFooter(),
            new TablistDoc.ListNames(true, ShowCondition.ALWAYS, new TablistDoc.ListNamePresentation("$player"), List.of()));
        String ordinary = TablistService.substituteTokens("&7$player", "Alex", "default");
        String animated = TablistService.substituteTokens(
            "{{ wave('$player', ['&a', '&b'], time.ticks) }}", "Alex", "vip");
        assertFalse(TablistService.requiresFastNameRefresh(ordinary, true));
        assertTrue(TablistService.requiresFastNameRefresh(animated, true));
        assertFalse(TablistService.requiresFastNameRefresh(animated, false));
        assertTrue(TablistService.fastDriverRequired(names, true, false, true, 40));
    }

    private static TablistDoc doc(boolean enabled, String header, String footer,
                                  List<TablistDoc.HeaderFooterVariant> variants) {
        return new TablistDoc(2, 1L, ShowCondition.ALWAYS,
            new TablistDoc.HeaderFooter(enabled, ShowCondition.ALWAYS,
                new TablistDoc.HeaderFooterPresentation(header, footer), variants),
            new TablistDoc.ListNames(true, ShowCondition.ALWAYS, new TablistDoc.ListNamePresentation("$player"), List.of()));
    }
}
