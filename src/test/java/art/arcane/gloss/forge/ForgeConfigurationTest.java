package art.arcane.gloss.forge;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.volmlib.util.config.ConfigExposePolicy;
import art.arcane.volmlib.util.config.TomlCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ForgeConfigurationTest {
    @Test
    void transportAndBuildLimitsNormalizeAndRoundTrip() throws Exception {
        GlossConfigFile file = new GlossConfigFile();
        file.forge.listenerThreads = Integer.MAX_VALUE;
        file.forge.listenerBacklog = -1;
        file.forge.buildDebounceTicks = Integer.MAX_VALUE;
        file.forge.buildQueueCapacity = -1;
        file.forge.maxBuildFiles = Integer.MAX_VALUE;
        file.forge.maxBuildBytes = Long.MAX_VALUE;
        file.forge.maxBuildPixels = -1;
        file.forge.maxRetainedArtifacts = -1;
        file.forge.maxRetainedBytes = Long.MAX_VALUE;
        file.forge.artifactRetentionSeconds = -1;
        file.sky.fadeIntervalTicks = Integer.MAX_VALUE;
        file.sky.maxPendingPerViewer = -1;
        file.sky.maxPendingOperations = -1;
        file.normalize();
        GlossConfig resolved = GlossConfig.from(file);
        assertEquals(32, resolved.modules().forge().listenerThreads());
        assertEquals(1, resolved.modules().forge().listenerBacklog());
        assertEquals(1200, resolved.modules().forge().buildDebounceTicks());
        assertEquals(1, resolved.modules().forge().buildQueueCapacity());
        assertEquals(new GlossConfig.PackLimits(65536, 1073741824L, 1, 2, 17179869184L, 1),
            resolved.modules().forge().limits());
        assertEquals(new GlossConfig.Sky(true, 200, 1, 16), resolved.modules().sky());
        GlossConfigFile restored = TomlCodec.fromToml(TomlCodec.toToml(file, "gloss", ConfigExposePolicy.ALL),
            GlossConfigFile.class);
        restored.normalize();
        assertEquals(resolved.modules().forge(), GlossConfig.from(restored).modules().forge());
        assertEquals(resolved.modules().sky(), GlossConfig.from(restored).modules().sky());
    }
}
