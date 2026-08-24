package art.arcane.gloss.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TextAnimationDemandTest {
    @Test
    public void ordinaryTextAndLivePlaceholdersKeepTheirConfiguredCadence() {
        assertFalse(TextPipeline.requiresFastRefresh(null));
        assertFalse(TextPipeline.requiresFastRefresh("plain"));
        assertFalse(TextPipeline.requiresFastRefresh("{{ player.ping }}"));
        assertFalse(TextPipeline.requiresFastRefresh("{{ fixed(server.tps, 1) }}"));
        assertFalse(TextPipeline.requiresFastRefresh("{{ papi('vault_prefix', '&7Member') }}"));
        assertFalse(TextPipeline.requiresFastRefresh("{{ metric('react.tick-ms', 0) }}"));
        assertFalse(TextPipeline.requiresFastRefresh("{{ 'time.seconds' }}"));
        assertFalse(TextPipeline.requiresFastRefresh("|metric.react.tps|"));
    }

    @Test
    public void timeDependenciesAndCompleteNamedAnimationsRequestFastSampling() {
        assertTrue(TextPipeline.requiresFastRefresh("{{ time.ms }}"));
        assertTrue(TextPipeline.requiresFastRefresh("{{ floor(time.ticks / 2) }}"));
        assertTrue(TextPipeline.requiresFastRefresh(
            "{{ player.health > 0 ? wave('LIVE', ['&a'], floor(time.seconds * 4)) : 'OUT' }}"));
        assertTrue(TextPipeline.requiresFastRefresh("prefix {{ player.name }} {{ time.seconds }}"));
        assertTrue(TextPipeline.requiresFastRefresh("|animation.rainbow|"));
    }

    @Test
    public void incompleteOrMalformedAnimationSourcesDoNotAccelerate() {
        assertFalse(TextPipeline.requiresFastRefresh("animation.rainbow"));
        assertFalse(TextPipeline.requiresFastRefresh("|animation.rainbow"));
        assertFalse(TextPipeline.requiresFastRefresh("{{ time.seconds"));
        assertFalse(TextPipeline.requiresFastRefresh("{{ time.seconds + }}"));
    }

    @Test
    public void viewerSpecificClassificationSeparatesGlobalAnimationFromPlayerText() {
        assertFalse(TextPipeline.viewerSpecific("|animation.rainbow|"));
        assertFalse(TextPipeline.viewerSpecific("{{ wave('LIVE', ['&a', '&b'], time.ticks) }}"));
        assertFalse(TextPipeline.viewerSpecific("{{ fixed(server.tps, 1) }}"));
        assertTrue(TextPipeline.viewerSpecific("%player_name%"));
        assertTrue(TextPipeline.viewerSpecific("|custom.player.function|"));
        assertTrue(TextPipeline.viewerSpecific("{{ player.name }}"));
        assertTrue(TextPipeline.viewerSpecific("{{ papi('vault_prefix', '') }}"));
    }
}
