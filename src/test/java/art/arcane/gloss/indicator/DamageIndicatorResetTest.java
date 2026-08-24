package art.arcane.gloss.indicator;

import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DamageIndicatorResetTest {
    @TempDir
    File folder;

    @Test
    void resetRestoresTheSingletonShippedDocument() throws IOException {
        ShippedDefaults defaults = defaults();
        defaults.extractMissing();
        File document = new File(folder, "default.json");
        Files.writeString(document.toPath(), "{\"schemaVersion\":1,\"revision\":9}");

        assertEquals(List.of("default"), defaults.resetToDefault("default"));
        assertEquals(shippedSource(), Files.readString(document.toPath()));
    }

    @Test
    void theResetTargetIsExactlyTheCanonicalSingleton() {
        assertEquals(List.of(DamageIndicatorSettingsDoc.DEFAULT_ID),
            ShippedDocumentCatalog.DAMAGE_INDICATORS.names());
    }

    private ShippedDefaults defaults() {
        return new ShippedDefaults(DamageIndicatorSettingsDoc.KIND, folder,
            ShippedDocumentCatalog.DAMAGE_INDICATORS.names());
    }

    private static String shippedSource() throws IOException {
        try (InputStream input = DamageIndicatorResetTest.class
            .getResourceAsStream("/defaults/damage-indicators/default.json")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
