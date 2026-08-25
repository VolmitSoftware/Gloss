package art.arcane.gloss.importer;

import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.config.GlossConfigLoader;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoloUiDataImporterTest {
    private static final String PANEL_JSON = """
        {
          "id": "spawn",
          "uuid": "00000000-0000-0000-0000-000000000901",
          "revision": 3,
          "rootMenuId": "main"
        }
        """;
    private static final String SETTINGS_JSON = """
        {
          "debugHitbox": true,
          "debugPosition": true,
          "builderUrl": "https://editor.example.com",
          "editorSyncEnabled": false,
          "editorSyncCreateToken": "abcdefghijklmnopqrstuv",
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
    private Path dataFolder;
    private GlossConfigLoader loader;

    @BeforeEach
    void seed() throws IOException {
        source = plugins.resolve("holoui");
        dataFolder = plugins.resolve("Gloss");
        Files.createDirectories(dataFolder);
        loader = new GlossConfigLoader(dataFolder.toFile());

        write(source.resolve("menus/main.json"), resourceBytes("/defaults/menus/default.json"));
        write(source.resolve("menus/shop/weapons.json"), resourceBytes("/defaults/menus/default.json"));
        write(source.resolve("menus/readme.txt"), "not a menu".getBytes(StandardCharsets.UTF_8));
        write(source.resolve("menus/.hidden/secret.json"), "{}".getBytes(StandardCharsets.UTF_8));
        write(source.resolve("images/logo.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        write(source.resolve("boards/spawn.json"), PANEL_JSON.getBytes(StandardCharsets.UTF_8));
        write(source.resolve("boards/hub/lobby.json"), PANEL_JSON.getBytes(StandardCharsets.UTF_8));
        write(source.resolve("previews/chest.json"), resourceBytes("/previews/chest.json"));
        write(source.resolve("previews/custom.json"), "{\"match\": {\"blocks\": [\"LECTERN\"]}}".getBytes(StandardCharsets.UTF_8));
        write(source.resolve("preview-scales.json"), "{\"scales\": {}}".getBytes(StandardCharsets.UTF_8));
        write(source.resolve("language.yml"), "language: en_US\n".getBytes(StandardCharsets.UTF_8));
        write(source.resolve("settings.json"), SETTINGS_JSON.getBytes(StandardCharsets.UTF_8));
        write(source.resolve("editor-sync-sessions.json"), "{\"sessions\": \"secret\"}".getBytes(StandardCharsets.UTF_8));
        write(source.resolve("editor-sync-transactions/txn.json"), "{}".getBytes(StandardCharsets.UTF_8));
        write(source.resolve("editor-sync-backups/backup.json"), "{}".getBytes(StandardCharsets.UTF_8));
        write(source.resolve("custom-items.json"), "{\"items\": []}".getBytes(StandardCharsets.UTF_8));
    }

    private HoloUiDataImporter importer() {
        return new HoloUiDataImporter(dataFolder.toFile(), loader);
    }

    @Test
    void mappingsCopyEverySurfaceAndNeverTouchTheSource() throws IOException {
        Map<String, String> before = snapshot(source);
        GlossConfigFile config = loader.loadForBoot();

        HoloUiDataImporter.Result result = importer().run(config, false);

        assertTrue(result.sourcePresent());
        assertArrayEquals(resourceBytes("/defaults/menus/default.json"), Files.readAllBytes(dataFolder.resolve("menus/main.json")));
        assertTrue(Files.isRegularFile(dataFolder.resolve("menus/shop/weapons.json")));
        assertFalse(Files.exists(dataFolder.resolve("menus/readme.txt")));
        assertFalse(Files.exists(dataFolder.resolve("menus/.hidden")));
        assertTrue(Files.isRegularFile(dataFolder.resolve("images/logo.png")));
        assertEquals(PANEL_JSON, Files.readString(dataFolder.resolve("panels/spawn.json"), StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(dataFolder.resolve("panels/hub/lobby.json")));
        assertFalse(Files.exists(dataFolder.resolve("boards")));
        assertTrue(Files.isRegularFile(dataFolder.resolve("previews/custom.json")));
        assertTrue(Files.isRegularFile(dataFolder.resolve("preview-scales.json")));
        assertTrue(Files.isRegularFile(dataFolder.resolve("language.yml")));
        assertTrue(Files.isRegularFile(dataFolder.resolve(HoloUiDataImporter.RECEIPT_FILE_NAME)));
        assertEquals(before, snapshot(source));
    }

    @Test
    void shippedIdenticalPreviewSkipsWhileModifiedPreviewCopies() throws IOException {
        GlossConfigFile config = loader.loadForBoot();

        HoloUiDataImporter.Result result = importer().run(config, false);

        assertFalse(Files.exists(dataFolder.resolve("previews/chest.json")));
        assertEquals(HoloUiImportDisposition.SKIPPED_SHIPPED_IDENTICAL,
            disposition(result, "previews/chest.json"));
        assertEquals(HoloUiImportDisposition.COPIED, disposition(result, "previews/custom.json"));
    }

    @Test
    void secretsAndRegenerableFilesNeverCopy() throws IOException {
        GlossConfigFile config = loader.loadForBoot();

        HoloUiDataImporter.Result result = importer().run(config, false);

        assertFalse(Files.exists(dataFolder.resolve("editor-sync-sessions.json")));
        assertFalse(Files.exists(dataFolder.resolve("editor-sync-transactions")));
        assertFalse(Files.exists(dataFolder.resolve("editor-sync-backups")));
        assertFalse(Files.exists(dataFolder.resolve("custom-items.json")));
        assertEquals(HoloUiImportDisposition.SKIPPED_SECRET, disposition(result, "editor-sync-sessions.json"));
        assertEquals(HoloUiImportDisposition.SKIPPED_SECRET, disposition(result, "editor-sync-transactions"));
        assertEquals(HoloUiImportDisposition.SKIPPED_SECRET, disposition(result, "editor-sync-backups"));
        assertEquals(HoloUiImportDisposition.SKIPPED_SECRET, disposition(result, "custom-items.json"));
    }

    @Test
    void settingsOverlayLandsInConfig() throws IOException {
        GlossConfigFile config = loader.loadForBoot();

        HoloUiDataImporter.Result result = importer().run(config, false);

        assertTrue(config.debug.hitbox);
        assertTrue(config.debug.position);
        assertEquals("https://editor.example.com", config.editor.builderUrl);
        assertFalse(config.editor.sync.enabled);
        assertEquals(GlossConfigFile.EDITOR_SYNC_ENDPOINT_DEFAULT, config.editor.sync.endpoint);
        assertEquals("abcdefghijklmnopqrstuv", config.editor.sync.createToken);
        assertEquals(120, config.editor.sync.sessionMinutes);
        assertEquals(7, config.editor.sync.pollSeconds);
        assertEquals(16, config.editor.sync.maxProjectMiB);
        assertFalse(config.features.previews);
        assertEquals(14.5D, config.preview.lookDistance);
        assertEquals(1.25D, config.preview.scale);
        assertEquals(2.0D, config.menus.uiScale);
        assertFalse(config.items.customItems);
        assertEquals(List.of("oraxen", "mmoitems", "nexo"), config.items.customItemProviders);
        String toml = Files.readString(dataFolder.resolve(GlossConfigLoader.FILE_NAME), StandardCharsets.UTF_8);
        assertTrue(toml.contains("lookDistance = 14.5"));
        assertTrue(toml.contains(GlossConfigFile.EDITOR_SYNC_ENDPOINT_DEFAULT));
        assertTrue(toml.contains("#"), "comments must regenerate on overlay save");
        assertTrue(loader.isSelfWrite(), "overlay save must register as a self write");
    }

    @Test
    void receiptRecordsEveryDisposition() throws IOException {
        GlossConfigFile config = loader.loadForBoot();
        importer().run(config, false);

        JsonObject receipt = JsonParser.parseString(
            Files.readString(dataFolder.resolve(HoloUiDataImporter.RECEIPT_FILE_NAME), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(HoloUiDataImporter.RECEIPT_SCHEMA_VERSION, receipt.get("schemaVersion").getAsInt());
        assertEquals(source.toFile().getAbsolutePath(), receipt.get("source").getAsString());
        assertFalse(receipt.get("force").getAsBoolean());
        assertTrue(receipt.get("importedAtMs").getAsLong() > 0L);

        JsonArray entries = receipt.getAsJsonArray("entries");
        assertEquals("copied", receiptDisposition(entries, "menus/main.json"));
        assertEquals("skipped-shipped-identical", receiptDisposition(entries, "previews/chest.json"));
        assertEquals("skipped-secret", receiptDisposition(entries, "editor-sync-sessions.json"));
        assertEquals("overlaid-config-key", receiptDisposition(entries, "settings.json:previewScale"));
    }

    @Test
    void receiptPresenceMakesTheBootRunANoOp() throws IOException {
        GlossConfigFile config = loader.loadForBoot();
        HoloUiDataImporter importer = importer();

        assertTrue(importer.shouldRun());
        importer.run(config, false);
        assertFalse(importer.shouldRun());
    }

    @Test
    void nonForceRerunNeverOverwritesExistingFiles() throws IOException {
        GlossConfigFile config = loader.loadForBoot();
        HoloUiDataImporter importer = importer();
        importer.run(config, false);
        Files.writeString(dataFolder.resolve("menus/main.json"), "operator edit", StandardCharsets.UTF_8);

        HoloUiDataImporter.Result rerun = importer.run(config, false);

        assertEquals("operator edit", Files.readString(dataFolder.resolve("menus/main.json"), StandardCharsets.UTF_8));
        assertEquals(HoloUiImportDisposition.SKIPPED_EXISTING, disposition(rerun, "menus/main.json"));
    }

    @Test
    void forceRerunOverwritesImportedFilesAndUpdatesTheReceipt() throws IOException {
        GlossConfigFile config = loader.loadForBoot();
        HoloUiDataImporter importer = importer();
        importer.run(config, false);
        Files.writeString(dataFolder.resolve("menus/main.json"), "operator edit", StandardCharsets.UTF_8);
        Map<String, String> before = snapshot(source);

        HoloUiDataImporter.Result rerun = importer.run(config, true);

        assertArrayEquals(resourceBytes("/defaults/menus/default.json"),
            Files.readAllBytes(dataFolder.resolve("menus/main.json")));
        assertEquals(HoloUiImportDisposition.COPIED, disposition(rerun, "menus/main.json"));
        assertEquals(before, snapshot(source));
        JsonObject receipt = JsonParser.parseString(
            Files.readString(dataFolder.resolve(HoloUiDataImporter.RECEIPT_FILE_NAME), StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(receipt.get("force").getAsBoolean());
    }

    @Test
    void brokenSettingsJsonRecordsAnErrorWhileCopiesProceed() throws IOException {
        Files.writeString(source.resolve("settings.json"), "{broken", StandardCharsets.UTF_8);
        GlossConfigFile config = loader.loadForBoot();

        HoloUiDataImporter.Result result = importer().run(config, false);

        assertEquals(HoloUiImportDisposition.ERROR, disposition(result, "settings.json"));
        assertEquals(HoloUiImportDisposition.COPIED, disposition(result, "menus/main.json"));
        assertEquals("{broken", Files.readString(source.resolve("settings.json"), StandardCharsets.UTF_8));
        assertEquals(GlossConfigFile.EDITOR_SYNC_ENDPOINT_DEFAULT, config.editor.sync.endpoint);
    }

    @Test
    void missingSourceDirectoryMeansNothingToRun() throws IOException {
        deleteRecursively(source);
        GlossConfigFile config = loader.loadForBoot();
        HoloUiDataImporter importer = importer();

        assertFalse(importer.shouldRun());
        HoloUiDataImporter.Result result = importer.run(config, false);
        assertFalse(result.sourcePresent());
        assertTrue(result.entries().isEmpty());
        assertFalse(Files.exists(dataFolder.resolve(HoloUiDataImporter.RECEIPT_FILE_NAME)));
    }

    private static HoloUiImportDisposition disposition(HoloUiDataImporter.Result result, String path) {
        Optional<HoloUiImportEntry> entry = result.entries().stream()
            .filter(candidate -> candidate.path().equals(path))
            .findFirst();
        assertTrue(entry.isPresent(), "missing receipt entry for " + path);
        return entry.get().disposition();
    }

    private static String receiptDisposition(JsonArray entries, String path) {
        for (int index = 0; index < entries.size(); index++) {
            JsonObject entry = entries.get(index).getAsJsonObject();
            if (entry.get("path").getAsString().equals(path)) {
                return entry.get("disposition").getAsString();
            }
        }
        return null;
    }

    private static byte[] resourceBytes(String path) throws IOException {
        try (InputStream stream = HoloUiDataImporterTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "missing classpath resource " + path);
            return stream.readAllBytes();
        }
    }

    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                hashes.put(root.relativize(path).toString(), sha256(Files.readAllBytes(path)));
            }
        }
        return hashes;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void write(Path path, byte[] content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, content);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.delete(path);
            }
        }
    }
}
