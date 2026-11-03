package art.arcane.gloss.text;

import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.expr.ExprVariableNamespace;
import art.arcane.gloss.expr.ExprVariableNamespaces;
import art.arcane.gloss.leaderboard.LeaderboardNamespace;
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

    @Test
    public void viewerRolesAndRegisteredFunctionsRenderPerViewer() {
        assertTrue(TextPipeline.viewerSpecific("{{ viewer.name }}"));
        assertTrue(TextPipeline.viewerSpecific("{{ viewer.health > 5 ? 'ok' : 'low' }}"));
        assertTrue(TextPipeline.viewerSpecific("{{ lang('showcase.title') }}"));
        assertTrue(TextPipeline.viewerSpecific("{{ upper(str(lang('showcase.title'))) }}"));
        assertFalse(TextPipeline.viewerSpecific("{{ subject.health }}"));
        assertFalse(TextPipeline.viewerSpecific("{{ metric('react.tps', 0) }}"));
    }

    @Test
    public void namespacesDecideWhichOfTheirVariablesRenderPerViewer() {
        ExprVariableNamespaces namespaces = ExprVariableNamespaces.global();
        namespaces.register(new ProbeNamespace());
        namespaces.register(new LeaderboardNamespace(id -> null));
        try {
            assertTrue(TextPipeline.viewerSpecific("{{ probe.loaded }}"));
            assertTrue(TextPipeline.viewerSpecific("{{ leaderboard.playtime.me.rank }}"));
            assertFalse(TextPipeline.viewerSpecific("{{ leaderboard.playtime.size }}"));
        } finally {
            namespaces.unregister("probe");
            namespaces.unregister(LeaderboardNamespace.PREFIX);
        }
    }

    private static final class ProbeNamespace implements ExprVariableNamespace {
        @Override
        public String prefix() {
            return "probe";
        }

        @Override
        public Object resolve(String suffix, ExprVariableContext context) {
            return null;
        }
    }
}
