package art.arcane.gloss.behavior;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.volmlib.util.config.ConfigExposePolicy;
import art.arcane.volmlib.util.config.TomlCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BehaviorConfigurationTest {
    @Test
    void aggregateTimerLimitsNormalizeAndRoundTrip() throws Exception {
        GlossConfigFile file = new GlossConfigFile();
        file.behaviors.maxTimersGlobal = Integer.MAX_VALUE;
        file.behaviors.maxTimersWithoutPlayer = -1;
        file.behaviors.maxTimersPerPlayer = 17;
        file.normalize();
        GlossConfig.Behaviors resolved = GlossConfig.from(file).modules().behaviors();
        assertEquals(1048576, resolved.maxTimersGlobal());
        assertEquals(1, resolved.maxTimersWithoutPlayer());
        assertEquals(17, resolved.maxTimersPerPlayer());

        GlossConfigFile restored = TomlCodec.fromToml(TomlCodec.toToml(file, "gloss", ConfigExposePolicy.ALL),
            GlossConfigFile.class);
        restored.normalize();

        assertEquals(resolved, GlossConfig.from(restored).modules().behaviors());
    }

    @Test
    void existingBehaviorConstructorUsesTheSameDefaultsAsTheConfigFile() {
        GlossConfig.Behaviors configured = GlossConfig.from(new GlossConfigFile()).modules().behaviors();
        GlossConfig.Behaviors constructed = new GlossConfig.Behaviors(configured.enabled(),
            configured.maxActionsPerTick(), configured.maxTimersPerPlayer(), configured.stateFlushSeconds(),
            configured.chatMaxWorkUnits());
        assertEquals(configured, constructed);
        assertEquals(8192, constructed.maxTimersGlobal());
        assertEquals(256, constructed.maxTimersWithoutPlayer());
    }
}
