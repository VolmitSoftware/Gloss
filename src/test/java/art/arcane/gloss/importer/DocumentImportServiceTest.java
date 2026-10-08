package art.arcane.gloss.importer;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.gloss.persistence.GlossProjectTransaction;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentImportServiceTest {
    private static final String BOARD = "{\"schemaVersion\":2,\"revision\":1}";

    @TempDir
    Path root;

    @Test
    void overwriteUsesTheReviewedValidDestinationsNextRevision() throws IOException {
        source();
        write(target(), "{\"schemaVersion\":2,\"revision\":41}");
        DocumentImportService service = service();
        DocumentImportPlan plan = service.preview(LegacyImportSource.FEATHERBOARD);

        assertEquals(42, JsonParser.parseString(plan.entries().getFirst().json())
            .getAsJsonObject().get("revision").getAsLong());
        service.apply(plan, true);
        assertEquals(42, JsonParser.parseString(Files.readString(target()))
            .getAsJsonObject().get("revision").getAsLong());
    }

    @Test
    void ignoredSourceEntriesConsumeThePreviewScanBudget() throws IOException {
        Path source = source();
        Files.writeString(source.resolveSibling("ignored.txt"), "ignored");
        GlossConfig.Imports limits = new GlossConfig.Imports(4096, 32768, 32, 600, 8, 32768, 2, 16);
        IOException failure = assertThrows(IOException.class,
            () -> service().preview(LegacyImportSource.FEATHERBOARD, limits));
        assertTrue(failure.getMessage().contains("visited-entry"));
        assertFalse(Files.exists(target()));
    }

    @Test
    void applyRescansIgnoredSourceEntriesWithTheCapturedBudget() throws IOException {
        Path source = source();
        DocumentImportService service = service();
        GlossConfig.Imports limits = new GlossConfig.Imports(4096, 32768, 32, 600, 8, 32768, 2, 16);
        DocumentImportPlan plan = service.preview(LegacyImportSource.FEATHERBOARD, limits);
        Files.writeString(source.resolveSibling("ignored.txt"), "added after preview");
        IOException failure = assertThrows(IOException.class, () -> service.apply(plan, false));
        assertTrue(failure.getMessage().contains("visited-entry"));
        assertFalse(Files.exists(target()));
    }

    @Test
    void invalidDestinationDoesNotSupplyATrustedRevision() throws IOException {
        source();
        write(target(), "{\"schemaVersion\":99,\"revision\":41}");
        DocumentImportPlan plan = service().preview(LegacyImportSource.FEATHERBOARD);

        assertEquals(1, plan.conflictCount());
        assertEquals(1, JsonParser.parseString(plan.entries().getFirst().json())
            .getAsJsonObject().get("revision").getAsLong());
    }

    @Test
    void changedSourceRejectsTheReviewedPlanWithoutWriting() throws IOException {
        Path source = source();
        DocumentImportService service = service();
        DocumentImportPlan plan = service.preview(LegacyImportSource.FEATHERBOARD);
        Files.writeString(source, "title: Changed\nlines: [Changed]\n");

        assertThrows(IOException.class, () -> service.apply(plan, false));
        assertFalse(Files.exists(target()));
        assertTrue(Files.readString(source).contains("Changed"));
    }

    @Test
    void changedDestinationRejectsOverwriteWithoutRecordingHistory() throws IOException {
        source();
        write(target(), BOARD);
        List<String> history = new ArrayList<>();
        DocumentImportService service = new DocumentImportService(root, data(), new GlossProjectTransaction(data()),
            new GlossPersistenceCoordinator(), (kind, id, content, origin) -> history.add(id));
        DocumentImportPlan plan = service.preview(LegacyImportSource.FEATHERBOARD);
        String changed = "{\"schemaVersion\":2,\"revision\":2}";
        Files.writeString(target(), changed);

        assertThrows(IOException.class, () -> service.apply(plan, true));
        assertEquals(changed, Files.readString(target()));
        assertTrue(history.isEmpty());
    }

    @Test
    void aNewDestinationAfterPreviewIsNeverOverwritten() throws IOException {
        source();
        DocumentImportService service = service();
        DocumentImportPlan plan = service.preview(LegacyImportSource.FEATHERBOARD);
        write(target(), BOARD);

        assertThrows(IOException.class, () -> service.apply(plan, true));
        assertEquals(BOARD, Files.readString(target()));
    }

    @Test
    void aDestinationCreatedAfterValidationStillFailsTheTransactionExpectation() throws IOException {
        Path source = source();
        Files.writeString(source, "title: [First, Second]\nlines: [Reviewed]\n");
        write(target(), BOARD);
        Path animation = data().resolve("animations/default-title.json");
        String concurrent = "{\"schemaVersion\":1,\"revision\":8,\"frames\":[\"Concurrent\"]}";
        DocumentImportService service = new DocumentImportService(root, data(), new GlossProjectTransaction(data()),
            new GlossPersistenceCoordinator(), (kind, id, content, origin) -> {
                try {
                    write(animation, concurrent);
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            });
        DocumentImportPlan plan = service.preview(LegacyImportSource.FEATHERBOARD);

        assertThrows(IOException.class, () -> service.apply(plan, true));
        assertEquals(concurrent, Files.readString(animation));
        assertEquals(BOARD, Files.readString(target()));
    }

    @Test
    void addedSourceAndProjectDocumentsInvalidateTheReviewedPlan() throws IOException {
        Path source = source();
        DocumentImportService service = service();
        DocumentImportPlan plan = service.preview(LegacyImportSource.FEATHERBOARD);
        Files.writeString(source.resolveSibling("extra.yml"), "title: Extra\nlines: [Extra]\n");
        assertThrows(IOException.class, () -> service.apply(plan, false));
        Files.delete(source.resolveSibling("extra.yml"));
        write(data().resolve("boards/extra.json"), BOARD);
        assertThrows(IOException.class, () -> service.apply(plan, false));
        assertFalse(Files.exists(target()));
    }

    @Test
    void invalidUnrelatedProjectDocumentRejectsPreview() throws IOException {
        source();
        write(data().resolve("boards/broken.json"), "{");
        assertThrows(RuntimeException.class, () -> service().preview(LegacyImportSource.FEATHERBOARD));
        assertFalse(Files.exists(target()));
    }

    @Test
    void boundedReadsRejectOversizedSourcesAndRetainedPlans() throws IOException {
        source();
        DocumentImportService service = service();
        GlossConfig.Imports smallFile = new GlossConfig.Imports(8, 64, 32, 600, 8, 64);
        assertThrows(IOException.class, () -> service.preview(LegacyImportSource.FEATHERBOARD, smallFile));
        GlossConfig.Imports smallPlan = new GlossConfig.Imports(512, 512, 32, 600, 8, 512);
        assertThrows(IOException.class, () -> service.preview(LegacyImportSource.FEATHERBOARD, smallPlan));
        assertFalse(Files.exists(target()));
    }

    @Test
    void successfulApplyPublishesTheReviewedContentAndPreservesTheSource() throws IOException {
        Path source = source();
        String original = Files.readString(source);
        DocumentImportService service = service();
        DocumentImportPlan plan = service.preview(LegacyImportSource.FEATHERBOARD);

        assertEquals(1, service.apply(plan, false).size());
        assertEquals(plan.entries().getFirst().json(), Files.readString(target()));
        assertEquals(original, Files.readString(source));
    }

    private DocumentImportService service() {
        return new DocumentImportService(root, data(), new GlossProjectTransaction(data()),
            new GlossPersistenceCoordinator(), (kind, id, content, origin) -> { });
    }

    private Path source() throws IOException {
        Path source = root.resolve(FeatherBoardScanner.RELATIVE_PATH).resolve("default.yml");
        write(source, "title: Reviewed\nlines: [Reviewed]\n");
        return source;
    }

    private Path data() {
        return root.resolve("plugins/Gloss");
    }

    private Path target() {
        return data().resolve("boards/default.json");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
