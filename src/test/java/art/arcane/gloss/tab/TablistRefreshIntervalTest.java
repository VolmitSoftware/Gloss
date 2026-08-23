package art.arcane.gloss.tab;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TablistRefreshIntervalTest {
    @Test
    void staticAndPlaceholderOnlyDocumentsKeepConfiguredCadence() {
        TablistDoc staticDoc = doc(true, "&aHeader", "&7Footer", true, Map.of("default", "$player"));
        TablistDoc placeholderDoc = doc(true, "%player_name%", "%server_online%", true,
            Map.of("default", "%vault_prefix% $player"));
        TablistDoc ordinaryExpressionDoc = doc(true, "{{ player.ping }}", "{{ fixed(server.tps, 1) }}", false,
            Map.of());

        assertEquals(40, TablistService.refreshIntervalTicks(staticDoc, List.of(), 40));
        assertEquals(40, TablistService.refreshIntervalTicks(placeholderDoc, List.of(), 40));
        assertEquals(40, TablistService.refreshIntervalTicks(ordinaryExpressionDoc, List.of(), 40));
    }

    @Test
    void expressionsAndAnimationFunctionsMatchEditorCadence() {
        TablistDoc expressionHeader = doc(true, "{{ wave('GLOSS', ['&a', '&b'], time.ticks) }}", "", false,
            Map.of());
        TablistDoc animationFooter = doc(true, "", "|animation.rainbow|", false, Map.of());
        TablistDoc expressionName = doc(false, "", "", true,
            Map.of("default", "{{ scanner('LIVE', '&7', '&a', time.ticks) }} $player"));

        assertEquals(1, TablistService.refreshIntervalTicks(expressionHeader, List.of(), 40));
        assertEquals(1, TablistService.refreshIntervalTicks(animationFooter, List.of(), 40));
        assertEquals(1, TablistService.refreshIntervalTicks(expressionName, List.of(), 40));
    }

    @Test
    void animationCadenceNeverSlowsAFasterOperatorSetting() {
        TablistDoc animated = doc(true, "|animation.rainbow|", "", false, Map.of());

        assertEquals(1, TablistService.refreshIntervalTicks(animated, List.of(), 1));
    }

    @Test
    void disabledSurfacesAndStaticOverridesDoNotAccelerateTheDriver() {
        TablistDoc disabled = doc(false, "{{ time.ticks }}", "|animation.rainbow|", false,
            Map.of("default", "{{ time.ticks }}"));
        TablistDoc staticDoc = doc(true, "", "", false, Map.of());

        assertEquals(40, TablistService.refreshIntervalTicks(disabled, List.of(), 40));
        assertEquals(40, TablistService.refreshIntervalTicks(staticDoc, List.of("&aOverride", "%player_name%"), 40));
    }

    @Test
    void animatedOverridesAccelerateOnlyWhenHeaderFooterIsEnabled() {
        List<String> animatedOverride = List.of("{{ time.ticks }}", "|animation.wave|");
        TablistDoc enabled = doc(true, "", "", false, Map.of());
        TablistDoc disabled = doc(false, "", "", false, Map.of());

        assertEquals(1, TablistService.refreshIntervalTicks(enabled, animatedOverride, 40));
        assertEquals(40, TablistService.refreshIntervalTicks(disabled, animatedOverride, 40));
    }

    private static TablistDoc doc(boolean useHeaderFooter, String header, String footer,
                                  boolean groupListNames, Map<String, String> nameFormats) {
        return new TablistDoc(1, 1L, useHeaderFooter, header, footer, groupListNames, nameFormats);
    }
}
