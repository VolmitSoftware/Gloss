package art.arcane.gloss.importer;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.volmlib.util.config.ConfigExposePolicy;
import art.arcane.volmlib.util.config.TomlCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportConfigurationTest {
    @Test
    void preparationLimitsNormalizeRoundTripAndPreservePriorConstructors() throws IOException {
        GlossConfigFile source = new GlossConfigFile();
        source.imports.maxPreparationBytes = Long.MAX_VALUE;
        source.imports.maxPreparationMillis = Long.MAX_VALUE;
        source.normalize();
        GlossConfig.Imports limits = GlossConfig.from(source).imports();
        assertEquals(17179869184L, limits.maxPreparationBytes());
        assertEquals(600000L, limits.maxPreparationMillis());
        String toml = TomlCodec.toToml(source, "gloss", ConfigExposePolicy.ALL);
        assertEquals(limits, GlossConfig.from(TomlCodec.fromToml(toml, GlossConfigFile.class)).imports());
        source.imports.maxPreparationBytes = 0;
        source.imports.maxPreparationMillis = 0;
        source.normalize();
        assertEquals(1024L, source.imports.maxPreparationBytes);
        assertEquals(1L, source.imports.maxPreparationMillis);
        GlossConfig.Imports original = new GlossConfig.Imports(1024, 2048, 8, 600, 2, 4096, 64, 8);
        assertEquals(1073741824L, original.maxPreparationBytes());
        assertEquals(30000L, original.maxPreparationMillis());
    }

    @Test
    void traversalDefaultsRemainAvailableThroughExistingConstructor() {
        GlossConfig.Imports original = new GlossConfig.Imports(1024, 2048, 8, 600, 2, 4096);
        assertEquals(65536, original.maxVisitedEntries());
        assertEquals(16, original.maxDirectoryDepth());
        GlossConfig.Imports defaults = GlossConfig.from(new GlossConfigFile()).imports();
        assertEquals(original.maxVisitedEntries(), defaults.maxVisitedEntries());
        assertEquals(original.maxDirectoryDepth(), defaults.maxDirectoryDepth());
    }

    @Test
    void traversalLimitsNormalizeAndRoundTripWithFieldDescriptions() throws IOException {
        GlossConfigFile source = new GlossConfigFile();
        source.imports.maxVisitedEntries = 0;
        source.imports.maxDirectoryDepth = 0;
        source.normalize();
        assertEquals(1, source.imports.maxVisitedEntries);
        assertEquals(1, source.imports.maxDirectoryDepth);
        source.imports.maxVisitedEntries = Integer.MAX_VALUE;
        source.imports.maxDirectoryDepth = Integer.MAX_VALUE;
        source.normalize();
        GlossConfig.Imports limits = GlossConfig.from(source).imports();
        assertEquals(1048576, limits.maxVisitedEntries());
        assertEquals(128, limits.maxDirectoryDepth());
        String toml = TomlCodec.toToml(source, "gloss", ConfigExposePolicy.ALL);
        assertTrue(toml.contains("maxVisitedEntries = 1048576"));
        assertTrue(toml.contains("maxDirectoryDepth = 128"));
        assertTrue(toml.contains("including directories, ignored files and symbolic links"));
        assertTrue(toml.contains("the collection root is depth 0"));
        GlossConfigFile restored = TomlCodec.fromToml(toml, GlossConfigFile.class);
        restored.normalize();
        assertEquals(limits, GlossConfig.from(restored).imports());
    }

    @Test
    void typedTraversalLimitsRejectValuesOutsideTheConfiguredBounds() {
        assertThrows(IllegalArgumentException.class,
            () -> new GlossConfig.Imports(1, 1, 1, 1, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class,
            () -> new GlossConfig.Imports(1, 1, 1, 1, 1, 1, 1048577, 1));
        assertThrows(IllegalArgumentException.class,
            () -> new GlossConfig.Imports(1, 1, 1, 1, 1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new GlossConfig.Imports(1, 1, 1, 1, 1, 1, 1, 129));
    }
}
