package art.arcane.gloss.drop;

import art.arcane.gloss.GlossConfig;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what {@code /gloss drops reset} does on disk: the command is a thin permission gate over
 * {@code DropNameService.resetToDefault}, which delegates the file work to {@link ShippedDefaults}.
 */
class RealDropResetTest {
    @TempDir
    File folder;

    private ShippedDefaults defaults() {
        return new ShippedDefaults(RealDropSettingsDoc.KIND, folder, ShippedDocumentCatalog.REAL_DROPS.names());
    }

    @Test
    void theRealDropsKindShipsExactlyTheOneDocumentTheResetCommandTargets() {
        assertEquals(List.of(RealDropSettingsDoc.DEFAULT_ID), ShippedDocumentCatalog.REAL_DROPS.names());
    }

    @Test
    void resetRestoresTheShippedFileByteForByteOverAnEditedOne() throws IOException {
        ShippedDefaults defaults = defaults();
        defaults.extractMissing();
        File document = new File(folder, RealDropSettingsDoc.DEFAULT_ID + ".json");
        Files.writeString(document.toPath(),
            "{\"schemaVersion\":3,\"revision\":9,\"presentation\":{\"limits\":{\"spread\":1}},"
                + "\"variants\":[],\"audience\":{\"when\":\"true\"}}");

        List<String> restored = defaults.resetToDefault(RealDropSettingsDoc.DEFAULT_ID);

        assertEquals(List.of(RealDropSettingsDoc.DEFAULT_ID), restored);
        assertArrayEqualsBytes(shippedBytes(), Files.readAllBytes(document.toPath()));
    }

    @Test
    void resetRecreatesTheDocumentWhenTheOperatorDeletedIt() throws IOException {
        ShippedDefaults defaults = defaults();
        defaults.extractMissing();
        File document = new File(folder, RealDropSettingsDoc.DEFAULT_ID + ".json");
        assertTrue(document.delete());

        assertEquals(List.of(RealDropSettingsDoc.DEFAULT_ID), defaults.resetToDefault(ShippedDefaults.ALL));
        assertArrayEqualsBytes(shippedBytes(), Files.readAllBytes(document.toPath()));
    }

    @Test
    void resetReportsNothingForANameTheKindDoesNotShip() {
        ShippedDefaults defaults = defaults();
        defaults.extractMissing();

        assertEquals(List.of(), defaults.resetToDefault("not-a-real-drops-document"));
    }

    @Test
    void theRestoredDocumentParsesBackToTheShippedRuntimeSettings() throws IOException {
        defaults().resetToDefault(ShippedDefaults.ALL);
        String raw = Files.readString(new File(folder, RealDropSettingsDoc.DEFAULT_ID + ".json").toPath(),
            StandardCharsets.UTF_8);

        GlossConfig.RealDrops restored = RealDropSettingsDoc.parse("default.json", raw).toConfig(true);
        GlossConfig.RealDrops shipped = RealDropSettingsDoc.DEFAULTS.toConfig(true);

        assertEquals(shipped, restored);
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        assertEquals(new String(expected, StandardCharsets.UTF_8), new String(actual, StandardCharsets.UTF_8));
    }

    private static byte[] shippedBytes() throws IOException {
        try (InputStream input = RealDropResetTest.class
            .getResourceAsStream("/defaults/real-drops/default.json")) {
            assertNotNull(input, "missing shipped real-drops default");
            return input.readAllBytes();
        }
    }
}
