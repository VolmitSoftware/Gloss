package art.arcane.gloss.hologram;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.volmlib.util.config.ConfigExposePolicy;
import art.arcane.volmlib.util.config.TomlCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemporaryDisplayConfigurationTest {
    @Test
    void panelCadencesClampAndKeepTheirExistingDefaults() throws IOException {
        GlossConfigFile source = new GlossConfigFile();
        assertEquals(new GlossConfig.Panels(source.features.panels), GlossConfig.from(source).panels());
        source.panels.visibilityIntervalTicks = 0;
        source.panels.followIntervalTicks = Integer.MAX_VALUE;
        source.panels.permissionCacheTicks = -1;
        source.normalize();
        GlossConfig.Panels panels = GlossConfig.from(source).panels();
        assertEquals(1, panels.visibilityIntervalTicks());
        assertEquals(1200, panels.followIntervalTicks());
        assertEquals(0, panels.permissionCacheTicks());
        String toml = TomlCodec.toToml(source, "gloss", ConfigExposePolicy.ALL);
        assertEquals(panels, GlossConfig.from(TomlCodec.fromToml(toml, GlossConfigFile.class)).panels());
    }

    @Test
    void defaultsPreserveExistingAdmissionLimitsAndConstructors() {
        GlossConfig config = GlossConfig.from(new GlossConfigFile());
        assertEquals(new GlossConfig.Bubbles(true), config.bubbles());
        assertEquals(new GlossConfig.Indicators(true), config.indicators());
        assertEquals(2048, config.bubbles().maxActive());
        assertEquals(2048, config.indicators().maxActive());
    }

    @Test
    void independentLimitsClampAndRoundTrip() throws IOException {
        GlossConfigFile source = new GlossConfigFile();
        source.temporaryDisplays.maxActiveBubbles = 0;
        source.temporaryDisplays.maxActiveIndicators = Integer.MAX_VALUE;
        source.normalize();
        GlossConfig config = GlossConfig.from(source);
        assertEquals(1, config.bubbles().maxActive());
        assertEquals(1048576, config.indicators().maxActive());
        String toml = TomlCodec.toToml(source, "gloss", ConfigExposePolicy.ALL);
        GlossConfig restored = GlossConfig.from(TomlCodec.fromToml(toml, GlossConfigFile.class));
        assertEquals(config.bubbles(), restored.bubbles());
        assertEquals(config.indicators(), restored.indicators());
    }
}
