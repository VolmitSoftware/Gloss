package art.arcane.gloss.importer;

import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigLoader;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.panel.PanelDefinition;
import art.arcane.gloss.panel.PanelTransform;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.gloss.persistence.GlossProjectTransaction;
import art.arcane.gloss.persistence.FailingImportTransaction;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoloUiDataImporterTest {
    private static final String SETTINGS = """
        {
          "debugHitbox": true,
          "debugPosition": true,
          "builderUrl": "https://editor.example.com",
          "editorSyncEnabled": false,
          "editorSyncCreateToken": "synthetic-private-credential",
          "editorSyncSessionMinutes": 120,
          "editorSyncPollSeconds": 7,
          "editorSyncMaxProjectMiB": 16,
          "previewEnabled": false,
          "previewLookDistance": 14.5,
          "previewScale": 1.25,
          "uiScale": 2.0,
          "customItems": false,
          "customItemProviders": "Oraxen, MMOItems ,,Nexo"
        }
        """;

    @TempDir
    Path plugins;
    private Path source;
    private Path root;
    private GlossConfigLoader loader;

    @BeforeEach
    void seed() throws IOException {
        source = plugins.resolve("holoui");
        root = plugins.resolve("Gloss");
        Files.createDirectories(root);
        loader = new GlossConfigLoader(root.toFile());
        loader.loadForBoot();
        write(source.resolve("menus/main.json"), menuFixture());
        write(source.resolve("menus/shop/weapons.json"), menuFixture());
        write(source.resolve("menus/readme.txt"), "not a menu");
        write(source.resolve("menus/.hidden/secret.json"), "{}");
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", image);
        write(source.resolve("images/logo.png"), image.toByteArray());
        write(source.resolve("boards/spawn.json"), panel("spawn"));
        write(source.resolve("boards/hub/lobby.json"), panel("hub/lobby"));
        write(source.resolve("previews/chest.json"), new String(resource("/previews/chest.json"),
            StandardCharsets.UTF_8).replace("gloss.preview.", "holoui.preview."));
        write(source.resolve("preview-scales.json"), "{\"00000000-0000-0000-0000-000000000001\":1.2}");
        write(source.resolve("settings.json"), SETTINGS);
        write(source.resolve("editor-sync-sessions.json"), "{\"sessions\":\"secret\"}");
        write(source.resolve("editor-sync-transactions/txn.json"), "{}");
        write(source.resolve("editor-sync-backups/backup.json"), "{}");
        write(source.resolve("custom-items.json"), "{\"items\":[]}");
    }

    private HoloUiDataImporter importer() {
        return importer(new GlossProjectTransaction(root));
    }

    @Test
    void sourceScanLimitsRejectIgnoredDeepDirectoriesBeforeStaging() throws IOException {
        Files.createDirectories(source.resolve("menus/.ignored/one/two"));
        GlossConfig.Imports limits = new GlossConfig.Imports(16777216, 134217728, 4096,
            600, 8, 268435456, 65536, 1);
        HoloUiDataImporter.Plan plan = importer().preview(false, limits);
        assertFalse(plan.ready());
        assertTrue(plan.entries().stream().anyMatch(entry -> entry.detail().contains("directory-depth")));
        assertTrue(plan.targets().isEmpty());
        assertFalse(Files.exists(root.resolve("menus")));
    }

    private HoloUiDataImporter importer(GlossProjectTransaction transaction) {
        return new HoloUiDataImporter(root.toFile(), new HoloUiDataImporter.Services(
            loader, transaction, new GlossPersistenceCoordinator()));
    }

    @Test
    void previewDoesNotWriteEitherDirectoryAndPublishesAnImmutablePlan() throws IOException {
        Map<String, String> beforeSource = snapshot(source);
        Map<String, String> beforeTarget = snapshot(root);

        HoloUiDataImporter.Plan plan = importer().preview(false);

        assertTrue(plan.ready(), plan.entries().toString());
        assertEquals(beforeSource, snapshot(source));
        assertEquals(beforeTarget, snapshot(root));
        assertThrows(UnsupportedOperationException.class, () -> plan.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.targets().clear());
    }

    @Test
    void importsEverySupportedSurfaceAndPreservesSourceAndPanelIdentities() throws IOException {
        Map<String, String> before = snapshot(source);

        HoloUiDataImporter.Result result = importer().run(false);

        assertTrue(result.applied(), result.entries().toString());
        assertTrue(result.sourcePresent());
        assertArrayEquals(menuFixture(), Files.readAllBytes(root.resolve("menus/main.json")));
        assertTrue(Files.isRegularFile(root.resolve("menus/shop/weapons.json")));
        assertFalse(Files.exists(root.resolve("menus/readme.txt")));
        assertFalse(Files.exists(root.resolve("menus/.hidden")));
        assertTrue(Files.isRegularFile(root.resolve("images/logo.png")));
        assertArrayEquals(Files.readAllBytes(source.resolve("boards/spawn.json")), Files.readAllBytes(root.resolve("panels/spawn.json")));
        assertTrue(Files.isRegularFile(root.resolve("panels/hub/lobby.json")));
        assertFalse(Files.exists(root.resolve("boards")));
        assertTrue(Files.isRegularFile(root.resolve("preview-scales.json")));
        assertTrue(Files.isRegularFile(root.resolve(HoloUiDataImporter.RECEIPT_FILE_NAME)));
        assertNotNull(result.backupPath());
        assertEquals(before, snapshot(source));
    }

    @Test
    void translatesHistoricalPreviewGlobalsAndLanguageKeys() throws IOException {
        HoloUiDataImporter.Result result = importer().run(false);

        assertTrue(result.applied(), result.entries().toString());
        String raw = Files.readString(root.resolve("previews/chest.json"));
        JsonObject preview = JsonParser.parseString(raw).getAsJsonObject();
        assertEquals(1.25D, preview.get("scale").getAsDouble());
        assertEquals(14.5D, preview.get("viewDistance").getAsDouble());
        assertFalse(raw.contains("holoui.preview."));
        assertTrue(raw.contains("gloss.preview."));
    }

    @Test
    void credentialsAndEditorStateNeverCopy() throws IOException {
        HoloUiDataImporter.Result result = importer().run(false);

        assertTrue(result.applied(), result.entries().toString());
        assertFalse(Files.exists(root.resolve("editor-sync-sessions.json")));
        assertFalse(Files.exists(root.resolve("editor-sync-transactions/txn.json")));
        assertFalse(Files.exists(root.resolve("custom-items.json")));
        assertEquals(HoloUiImportDisposition.SKIPPED_SECRET, disposition(result, "settings.json:editorSyncCreateToken"));
        assertEquals("", loader.loadForReload().editor.sync.createToken);
        assertFalse(Files.readString(root.resolve(GlossConfigLoader.FILE_NAME)).contains("synthetic-private-credential"));
        assertFalse(Files.readString(root.resolve(HoloUiDataImporter.RECEIPT_FILE_NAME)).contains("synthetic-private-credential"));
    }

    @Test
    void importsTypedSettingsWithoutMutatingPreviouslyLoadedConfiguration() throws IOException {
        GlossConfigFile prior = loader.loadForReload();

        HoloUiDataImporter.Result result = importer().run(false);

        assertTrue(result.applied(), result.entries().toString());
        GlossConfigFile updated = loader.loadForReload();
        assertFalse(prior.debug.hitbox);
        assertTrue(updated.debug.hitbox);
        assertTrue(updated.debug.position);
        assertEquals("https://editor.example.com", updated.editor.builderUrl);
        assertFalse(updated.editor.sync.enabled);
        assertEquals(120, updated.editor.sync.sessionMinutes);
        assertEquals(7, updated.editor.sync.pollSeconds);
        assertEquals(16, updated.editor.sync.maxProjectMiB);
        assertFalse(updated.features.previews);
        assertEquals(2.0D, updated.menus.uiScale);
        assertFalse(updated.items.customItems);
        assertEquals(List.of("oraxen", "mmoitems", "nexo"), updated.items.customItemProviders);
        assertTrue(Files.readString(root.resolve(GlossConfigLoader.FILE_NAME)).contains("#"));
    }

    @Test
    void unchangedRepeatedImportDoesNotRewriteReceiptOrConfiguration() throws IOException {
        HoloUiDataImporter importer = importer();
        assertTrue(importer.shouldRun());
        assertTrue(importer.run(false).applied());
        Map<String, String> before = snapshot(root);

        HoloUiDataImporter.Result result = importer.run(false);

        assertTrue(result.applied(), result.entries().toString());
        assertEquals(before, snapshot(root));
        assertEquals(null, result.backupPath());
        assertFalse(importer.shouldRun());
    }

    @Test
    void destinationConflictPreventsEveryWriteIncludingReceipt() throws IOException {
        write(root.resolve("menus/main.json"), "{\"components\":[]}");
        Map<String, String> before = snapshot(root);

        HoloUiDataImporter.Result result = importer().run(false);

        assertFalse(result.applied());
        assertEquals(HoloUiImportDisposition.CONFLICT, disposition(result, "menus/main.json"));
        assertEquals(before, snapshot(root));
        assertFalse(Files.exists(root.resolve(HoloUiDataImporter.RECEIPT_FILE_NAME)));
    }

    @Test
    void explicitOverwriteArchivesTheOldDestination() throws IOException {
        write(root.resolve("menus/main.json"), "{\"components\":[]}");

        HoloUiDataImporter.Result result = importer().run(true);

        assertTrue(result.applied(), result.entries().toString());
        assertArrayEquals(menuFixture(), Files.readAllBytes(root.resolve("menus/main.json")));
        assertEquals("{\"components\":[]}", Files.readString(Path.of(result.backupPath()).resolve("menus/main.json")));
    }

    @Test
    void changedSourceBytesAfterPreviewRejectTheWholeApply() throws IOException {
        HoloUiDataImporter importer = importer();
        HoloUiDataImporter.Plan plan = importer.preview(false);
        write(source.resolve("menus/main.json"), "{\"components\":[]}");
        Map<String, String> before = snapshot(root);

        HoloUiDataImporter.Result result = importer.apply(plan);

        assertFalse(result.applied());
        assertEquals(before, snapshot(root));
        assertTrue(result.count(HoloUiImportDisposition.ERROR) > 0);
    }

    @Test
    void addedSourceFileAfterPreviewRejectsApply() throws IOException {
        HoloUiDataImporter importer = importer();
        HoloUiDataImporter.Plan plan = importer.preview(false);
        write(source.resolve("menus/new.json"), menuFixture());

        assertFalse(importer.apply(plan).applied());
        assertFalse(Files.exists(root.resolve("menus/main.json")));
    }

    @Test
    void changedDestinationAfterPreviewRejectsApply() throws IOException {
        HoloUiDataImporter importer = importer();
        HoloUiDataImporter.Plan plan = importer.preview(false);
        write(root.resolve("menus/main.json"), "{\"components\":[]}");

        assertFalse(importer.apply(plan).applied());
        assertEquals("{\"components\":[]}", Files.readString(root.resolve("menus/main.json")));
        assertFalse(Files.exists(root.resolve("images/logo.png")));
    }

    @Test
    void malformedSettingsPreventsTheImport() throws IOException {
        write(source.resolve("settings.json"), "{broken");
        Map<String, String> before = snapshot(root);

        HoloUiDataImporter.Result result = importer().run(false);

        assertFalse(result.applied());
        assertTrue(result.count(HoloUiImportDisposition.ERROR) > 0);
        assertEquals(before, snapshot(root));
    }

    @Test
    void invalidSourceDocumentPreventsEveryCopy() throws IOException {
        write(source.resolve("boards/spawn.json"), "{\"schemaVersion\":999}");

        HoloUiDataImporter.Result result = importer().run(false);

        assertFalse(result.applied());
        assertEquals(HoloUiImportDisposition.ERROR, disposition(result, "boards/spawn.json"));
        assertFalse(Files.exists(root.resolve("menus/main.json")));
    }

    @Test
    void invalidMenuActionsCannotBeSilentlyDroppedByAnExactImport() throws IOException {
        JsonObject menu = JsonParser.parseString(Files.readString(source.resolve("menus/main.json"))).getAsJsonObject();
        JsonObject button = menu.getAsJsonArray("components").get(2).getAsJsonObject().getAsJsonObject("data");
        button.add("actions", JsonParser.parseString("[{\"type\":\"command\",\"command\":\"\"}]"));
        write(source.resolve("menus/main.json"), menu.toString());

        HoloUiDataImporter.Result result = importer().run(false);

        assertFalse(result.applied());
        assertTrue(result.count(HoloUiImportDisposition.ERROR) > 0);
        assertFalse(Files.exists(root.resolve("menus/main.json")));
    }

    @Test
    void invalidExistingCurrentDocumentBlocksActivation() throws IOException {
        write(root.resolve("emoji/broken.json"), "{\"schemaVersion\":999}");

        assertFalse(importer().run(false).applied());
        assertFalse(Files.exists(root.resolve("menus/main.json")));
    }

    @Test
    void missingPanelMenuReferenceBlocksActivation() throws IOException {
        Files.delete(source.resolve("menus/main.json"));

        HoloUiDataImporter.Result result = importer().run(false);

        assertFalse(result.applied());
        assertTrue(result.entries().stream().anyMatch(entry -> entry.detail() != null
            && entry.detail().contains("root menu does not exist")), result.entries().toString());
    }

    @Test
    void duplicatePanelIdentityBlocksActivation() throws IOException {
        JsonObject panel = JsonParser.parseString(Files.readString(source.resolve("boards/spawn.json"))).getAsJsonObject();
        panel.addProperty("id", "hub/lobby");
        write(source.resolve("boards/hub/lobby.json"), panel.toString());

        assertFalse(importer().run(false).applied());
        assertFalse(Files.exists(root.resolve("panels/spawn.json")));
    }

    @Test
    void unknownSettingsAreReportedAsUnsupportedRatherThanSilentlyDiscarded() throws IOException {
        JsonObject settings = JsonParser.parseString(SETTINGS).getAsJsonObject();
        settings.addProperty("unrecognizedFeature", true);
        write(source.resolve("settings.json"), settings.toString());

        HoloUiDataImporter.Result result = importer().run(false);

        assertFalse(result.applied());
        assertEquals(HoloUiImportDisposition.UNSUPPORTED, disposition(result, "settings.json:unrecognizedFeature"));
    }

    @Test
    void customizedDestinationSettingsRequireExplicitOverwrite() throws IOException {
        GlossConfigFile customized = loader.loadForReload();
        customized.menus.uiScale = 3D;
        loader.save(customized);

        HoloUiDataImporter.Result result = importer().run(false);

        assertFalse(result.applied());
        assertEquals(HoloUiImportDisposition.CONFLICT, disposition(result, GlossConfigLoader.FILE_NAME));
        assertEquals(3D, loader.loadForReload().menus.uiScale);
    }

    @Test
    void aMidCommitFailureRollsBackDocumentsConfigurationAndReceipt() throws IOException {
        Map<String, String> before = snapshot(root);
        AtomicBoolean injected = new AtomicBoolean();
        GlossProjectTransaction transaction = FailingImportTransaction.afterMenuWrite(root, injected);

        HoloUiDataImporter.Result result = importer(transaction).run(false);

        assertFalse(result.applied());
        assertTrue(injected.get());
        Map<String, String> live = snapshot(root);
        live.keySet().removeIf(path -> path.startsWith("editor-sync-backups/") || path.startsWith("editor-sync-transactions/"));
        assertEquals(before, live);
        assertFalse(Files.exists(root.resolve(HoloUiDataImporter.RECEIPT_FILE_NAME)));
    }

    @Test
    void planCannotBeAppliedByAnotherImporter() {
        HoloUiDataImporter.Plan plan = importer().preview(false);
        assertThrows(IllegalArgumentException.class, () -> importer().apply(plan));
    }

    @Test
    void noSourceProducesNoReceipt() throws IOException {
        delete(source);
        HoloUiDataImporter importer = importer();

        HoloUiDataImporter.Result result = importer.run(false);

        assertFalse(result.sourcePresent());
        assertFalse(result.applied());
        assertTrue(result.entries().isEmpty());
        assertFalse(importer.shouldRun());
        assertFalse(Files.exists(root.resolve(HoloUiDataImporter.RECEIPT_FILE_NAME)));
    }

    private static HoloUiImportDisposition disposition(HoloUiDataImporter.Result result, String path) {
        return result.entries().stream().filter(entry -> entry.path().equals(path)).findFirst().orElseThrow().disposition();
    }

    private static String panel(String id) {
        return DocumentParsers.GSON.toJson(PanelDefinition.create(id, "main",
            PanelTransform.at("minecraft:overworld", UUID.fromString("00000000-0000-0000-0000-000000000010"),
                0, 64, 0, 0)));
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream stream = HoloUiDataImporterTest.class.getResourceAsStream(path)) {
            assertNotNull(stream);
            return stream.readAllBytes();
        }
    }

    private static Map<String, String> snapshot(Path directory) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        try (Stream<Path> stream = Files.walk(directory)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                hashes.put(directory.relativize(path).toString(), sha256(Files.readAllBytes(path)));
            }
        }
        return hashes;
    }

    private static byte[] menuFixture() throws IOException {
        JsonObject menu = JsonParser.parseString(new String(resource("/defaults/menus/default.json"),
            StandardCharsets.UTF_8)).getAsJsonObject();
        menu.getAsJsonArray("components").get(2).getAsJsonObject().getAsJsonObject("data")
            .getAsJsonArray("actions").remove(0);
        return menu.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void write(Path file, String content) throws IOException {
        write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    private static void delete(Path directory) throws IOException {
        try (Stream<Path> stream = Files.walk(directory)) {
            for (Path file : stream.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.delete(file);
            }
        }
    }
}
