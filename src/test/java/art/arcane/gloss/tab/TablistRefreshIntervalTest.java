package art.arcane.gloss.tab;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TablistRefreshIntervalTest {
    @Test
    void staticAndPlaceholderOnlyDocumentsKeepConfiguredCadence() {
        TablistDoc staticDoc = doc(true, "&aHeader", "&7Footer", true, Map.of("default", "$player"));
        TablistDoc placeholderDoc = doc(true, "%player_name%", "%server_online%", true,
            Map.of("default", "%vault_prefix% $player"));
        TablistDoc ordinaryExpressionDoc = doc(true, "{{ player.ping }}", "{{ fixed(server.tps, 1) }}", false,
            Map.of());

        assertEquals(40, TablistService.refreshIntervalTicks(staticDoc, 40));
        assertEquals(40, TablistService.refreshIntervalTicks(placeholderDoc, 40));
        assertEquals(40, TablistService.refreshIntervalTicks(ordinaryExpressionDoc, 40));
    }

    @Test
    void animatedHeaderAndFooterUseFastGlobalCadenceButNamesDoNot() {
        TablistDoc expressionHeader = doc(true, "{{ wave('GLOSS', ['&a', '&b'], time.ticks) }}", "", false,
            Map.of());
        TablistDoc animationFooter = doc(true, "", "|animation.rainbow|", false, Map.of());
        TablistDoc expressionName = doc(false, "", "", true,
            Map.of("default", "{{ scanner('LIVE', '&7', '&a', time.ticks) }} $player"));

        assertEquals(1, TablistService.refreshIntervalTicks(expressionHeader, 40));
        assertEquals(1, TablistService.refreshIntervalTicks(animationFooter, 40));
        assertEquals(40, TablistService.refreshIntervalTicks(expressionName, 40));
    }

    @Test
    void animationCadenceNeverSlowsAFasterOperatorSetting() {
        TablistDoc animated = doc(true, "|animation.rainbow|", "", false, Map.of());

        assertEquals(1, TablistService.refreshIntervalTicks(animated, 1));
    }

    @Test
    void disabledSurfacesAndStaticDocumentsDoNotAccelerateTheDriver() {
        TablistDoc disabled = doc(false, "{{ time.ticks }}", "|animation.rainbow|", false,
            Map.of("default", "{{ time.ticks }}"));
        TablistDoc staticDoc = doc(true, "", "", false, Map.of());

        assertEquals(40, TablistService.refreshIntervalTicks(disabled, 40));
        assertEquals(40, TablistService.refreshIntervalTicks(staticDoc, 40));
    }

    @Test
    void animatedOverridesUseASeparateFastDriverOnlyWhenNeeded() {
        TablistDoc enabled = doc(true, "", "", false, Map.of());
        TablistDoc disabled = doc(false, "", "", false, Map.of());

        assertEquals(40, TablistService.refreshIntervalTicks(enabled, 40));
        assertEquals(40, TablistService.refreshIntervalTicks(disabled, 40));
        assertTrue(TablistService.fastDriverRequired(enabled, true, true, false, 40));
        assertFalse(TablistService.fastDriverRequired(disabled, true, true, false, 40));
        assertFalse(TablistService.fastDriverRequired(enabled, false, true, false, 40));
        assertFalse(TablistService.fastDriverRequired(enabled, true, false, false, 40));
        assertFalse(TablistService.fastDriverRequired(enabled, true, true, false, 1));
    }

    @Test
    void fastNameDriverTracksOnlyPlayersWhoseSelectedFormatIsAnimated() {
        TablistDoc names = doc(false, "", "", true, Map.of(
            "default", "&7$player",
            "vip", "{{ wave($player, ['&a', '&b'], time.ticks) }}"));
        TablistService.ListNameChoice ordinary = TablistService.chooseListName(
            false, "default", names.nameFormats());
        TablistService.ListNameChoice animated = TablistService.chooseListName(
            false, "vip", names.nameFormats());

        assertFalse(TablistService.requiresFastNameRefresh(
            TablistService.substituteTokens(ordinary.template(), "Alex", ordinary.groupName()), true));
        assertTrue(TablistService.requiresFastNameRefresh(
            TablistService.substituteTokens(animated.template(), "Alex", animated.groupName()), true));
        assertFalse(TablistService.requiresFastNameRefresh(
            TablistService.substituteTokens(animated.template(), "Alex", animated.groupName()), false));
        assertTrue(TablistService.fastDriverRequired(names, true, false, true, 40));
        assertFalse(TablistService.fastDriverRequired(names, true, false, false, 40));
    }

    private static TablistDoc doc(boolean useHeaderFooter, String header, String footer,
                                  boolean groupListNames, Map<String, String> nameFormats) {
        return new TablistDoc(1, 1L, useHeaderFooter, header, footer, groupListNames, nameFormats);
    }
}
