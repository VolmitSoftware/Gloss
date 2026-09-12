package art.arcane.gloss.bedrock;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.menu.CharacterizationSupport;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The render paths ask {@link BedrockPolicy#of(Gloss)} for their policy, so it has to answer null
 * for every state in which the plugin has no policy yet instead of hiding everything.
 */
class BedrockAudienceTest {
    @Test
    void policyOfPluginIsNullWithoutAPlugin() {
        assertNull(BedrockPolicy.of(null));
    }

    @Test
    void policyIsNullBeforeTheServiceIsBuiltAndTheInstalledOneAfterwards() throws Exception {
        Server server = CharacterizationSupport.server(Map.of());
        Object previousServer = CharacterizationSupport.installServer(server);
        try {
            Gloss plugin = CharacterizationSupport.bareGloss(server);
            assertNull(BedrockPolicy.of(plugin), "a plugin whose Bedrock service is not built yet never hides");

            BedrockPolicy policy = new BedrockPolicy(new BedrockService(BedrockService.Detection.UUID),
                () -> new GlossConfig.Bedrock("uuid", true, true, true, true, true, true));
            CharacterizationSupport.setField(plugin, "bedrockPolicy", policy);
            assertSame(policy, BedrockPolicy.of(plugin));
        } finally {
            CharacterizationSupport.restoreServer((Server) previousServer);
        }
    }
}
