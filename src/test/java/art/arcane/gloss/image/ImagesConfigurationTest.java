package art.arcane.gloss.image;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.volmlib.util.config.ConfigExposePolicy;
import art.arcane.volmlib.util.config.TomlCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImagesConfigurationTest {
    @Test
    void sourceCacheAndQueueLimitsNormalizeAndRoundTrip() throws Exception {
        GlossConfigFile file = new GlossConfigFile();
        file.images.maxFileBytes = Integer.MAX_VALUE;
        file.images.maxPixels = -1;
        file.images.maxDimension = -1;
        file.images.rasterMaxDimension = Integer.MAX_VALUE;
        file.images.cacheBytes = Integer.MAX_VALUE;
        file.images.maxEntries = -1;
        file.images.maxPending = -1;
        file.images.workerThreads = Integer.MAX_VALUE;
        file.normalize();
        GlossConfig.Images limits = GlossConfig.from(file).images();
        assertEquals(new GlossConfig.Images(268435456, 256, 16, 128, 1073741824, 1, 1, 4), limits);
        GlossConfigFile restored = TomlCodec.fromToml(TomlCodec.toToml(file, "gloss", ConfigExposePolicy.ALL),
            GlossConfigFile.class);
        restored.normalize();
        assertEquals(limits, GlossConfig.from(restored).images());
    }
}
