package art.arcane.gloss.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A file written for another build is ignored, but it must not vanish quietly: the ledger names
 * both schema numbers so the operator can see which way the file has to move, and the count is
 * visible while the file sits there.
 */
class DocumentRegistrySkipReportTest {
    record Doc(int schemaVersion, long revision) {
        Doc {
            DocumentEnvelope.requireSchemaVersion("skiptest", schemaVersion, 1);
            DocumentEnvelope.requireRevision("skiptest", revision);
        }
    }

    private static DocumentRegistry<Doc> registry(File folder) {
        return DocumentRegistry.folder("skiptest", folder,
            (name, raw) -> DocumentParsers.parseJson(name, raw, Doc.class), Doc::revision);
    }

    @Test
    void skippedFilesAreLedgeredWithBothSchemaVersions(@TempDir Path dir) throws Exception {
        File folder = dir.resolve("skiptest").toFile();
        assertTrue(folder.mkdirs());
        Files.writeString(folder.toPath().resolve("old.json"), "{\"schemaVersion\":9,\"revision\":1}",
            StandardCharsets.UTF_8);
        DocumentRegistry<Doc> registry = registry(folder);

        registry.reload();

        Map<String, String> skipped = registry.unsupportedSchemaDocuments();
        assertEquals(1, skipped.size());
        assertTrue(skipped.get("old").contains("9"), skipped.get("old"));
        assertTrue(skipped.get("old").contains("1"), skipped.get("old"));
        assertTrue(registry.snapshot().isEmpty());
        registry.close();
    }

    @Test
    void aLiveRegistryContributesItsSkippedFilesToTheTotal(@TempDir Path dir) throws Exception {
        File folder = dir.resolve("skiptest").toFile();
        assertTrue(folder.mkdirs());
        Files.writeString(folder.toPath().resolve("old.json"), "{\"schemaVersion\":9,\"revision\":1}",
            StandardCharsets.UTF_8);
        int before = DocumentRegistry.unsupportedSchemaTotal();
        DocumentRegistry<Doc> registry = registry(folder);

        registry.reload();

        assertEquals(before + 1, DocumentRegistry.unsupportedSchemaTotal());
        registry.close();
        assertEquals(before, DocumentRegistry.unsupportedSchemaTotal(),
            "a closed registry stops contributing to the status count");
    }

    @Test
    void aFileThatIsFixedLeavesTheLedger(@TempDir Path dir) throws Exception {
        File folder = dir.resolve("skiptest").toFile();
        assertTrue(folder.mkdirs());
        Path file = folder.toPath().resolve("old.json");
        Files.writeString(file, "{\"schemaVersion\":9,\"revision\":1}", StandardCharsets.UTF_8);
        DocumentRegistry<Doc> registry = registry(folder);
        registry.reload();
        assertEquals(1, registry.unsupportedSchemaDocuments().size());

        Files.writeString(file, "{\"schemaVersion\":1,\"revision\":1}", StandardCharsets.UTF_8);
        registry.reload();

        assertEquals(Map.of(), registry.unsupportedSchemaDocuments());
        assertEquals(1, registry.snapshot().size());
        registry.close();
    }
}
