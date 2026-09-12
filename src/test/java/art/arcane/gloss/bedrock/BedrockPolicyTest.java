package art.arcane.gloss.bedrock;

import art.arcane.gloss.GlossConfig;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockPolicyTest {
    private static final UUID BEDROCK = new UUID(0L, 42L);
    private static final UUID JAVA = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    private static GlossConfig.Bedrock config(boolean all) {
        return new GlossConfig.Bedrock("uuid", all, all, all, all, all, all);
    }

    @Test
    void hidesConfiguredSurfacesForBedrockViewersOnly() {
        BedrockPolicy policy = new BedrockPolicy(new BedrockService(BedrockService.Detection.UUID), () -> config(true));
        assertTrue(policy.hides(BedrockSurface.HOLOGRAM, BEDROCK));
        assertFalse(policy.hides(BedrockSurface.HOLOGRAM, JAVA));
        assertFalse(policy.hides(BedrockSurface.HOLOGRAM, (UUID) null));
    }

    @Test
    void perSurfaceSwitchesAreHonoured() {
        GlossConfig.Bedrock only = new GlossConfig.Bedrock("uuid", true, false, false, false, false, false);
        BedrockPolicy policy = new BedrockPolicy(new BedrockService(BedrockService.Detection.UUID), () -> only);
        assertTrue(policy.hides(BedrockSurface.HOLOGRAM, BEDROCK));
        assertFalse(policy.hides(BedrockSurface.PANEL, BEDROCK));
        assertFalse(policy.hides(BedrockSurface.DROP, BEDROCK));
    }

    @Test
    void detectionOffNeverHides() {
        BedrockPolicy policy = new BedrockPolicy(new BedrockService(BedrockService.Detection.OFF), () -> config(true));
        assertFalse(policy.hides(BedrockSurface.OVERLAY, BEDROCK));
    }
}
