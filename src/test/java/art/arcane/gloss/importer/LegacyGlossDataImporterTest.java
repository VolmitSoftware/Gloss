package art.arcane.gloss.importer;


import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.board.BoardDoc;
import art.arcane.gloss.doc.DocumentPresetCatalog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import art.arcane.gloss.behavior.BehaviorDoc;
import art.arcane.gloss.animation.AnimationDoc;
import art.arcane.gloss.bubble.BubbleStyleDoc;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.config.GlossConfigLoader;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.drop.RealDropSettingsDoc;
import art.arcane.gloss.emoji.EmojiDoc;
import art.arcane.gloss.hologram.HologramDoc;
import art.arcane.gloss.motd.MotdDoc;
import art.arcane.gloss.tab.TablistDoc;
import art.arcane.gloss.indicator.DamageIndicatorSettingsDoc;
import art.arcane.gloss.persistence.GlossProjectTransaction;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.gloss.doc.DocumentParsers;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyGlossDataImporterTest {
    private static final String LEGACY_HOLOGRAM = """
        {
          "id": "spawn",
          "world": "world",
          "x": 10.5,
          "y": 70.0,
          "z": -4.25,
          "lines": ["&aWelcome", "&7Second line"]
        }
        """;
    private static final String LEGACY_EMOJI_HEART = """
        {
          "trigger": "<3",
          "emoji": "U+2764;",
          "enabled": true
        }
        """;
    private static final String LEGACY_EMOJI_NO_TRIGGER = """
        {
          "trigger": "<uses :id:>",
          "emoji": "U+2708;"
        }
        """;
    private static final String LEGACY_ANIMATION = """
        {
          "target-framerate": 2.0,
          "animation-type": "ASCEND",
          "frames": ["&cOne", "&6Two"]
        }
        """;

    @TempDir
    Path dataFolder;

    private GlossConfigLoader loader;

    @BeforeEach
    void setUp() {
        loader = new GlossConfigLoader(dataFolder.toFile());
    }

    private LegacyGlossDataImporter importer() {
        return new LegacyGlossDataImporter(dataFolder.toFile(), new LegacyGlossDataImporter.Services(
            loader, new GlossProjectTransaction(dataFolder), new GlossPersistenceCoordinator()));
    }

    @Test
    void historicalProjectSurvivesCanonicalEditorEditsAndPresetReload() throws IOException {
        JsonObject fixture;
        try (InputStream input = getClass().getResourceAsStream("/importer/editor-roundtrip.json")) {
            assertNotNull(input);
            fixture = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        write("boards/event.json", fixture.get("historicalBoard").toString());
        write("config.yml", fixture.get("historicalConfig").getAsString());
        LegacyGlossDataImporter.Result imported = importer().run();
        assertEquals(LegacyGlossDataImporter.Status.MIGRATED, status(imported, "boards/event.json"));
        assertNotNull(imported.backupPath());
        JsonObject canonical = fixture.getAsJsonObject("canonical");
        assertEquals(BoardDoc.parse("event.json", canonical.get("board").toString()),
            BoardDoc.parse("event.json", read("boards/event.json")));
        assertEquals(MotdDoc.parse("motd.json", canonical.get("motd").toString()),
            MotdDoc.parse("motd.json", read("motd.json")));

        JsonObject edited = fixture.getAsJsonObject("edited");
        write("presets.json", fixture.get("presets").toString());
        write("boards/event.json", edited.get("board").toString());
        write("motd.json", edited.get("motd").toString());
        new PreparedImport(dataFolder).validateDocuments();
        DocumentPresetCatalog presets = DocumentPresetCatalog.read(dataFolder);
        BoardDoc reloaded = BoardDoc.parse("event.json", presets.resolve("boards", read("boards/event.json")));
        assertEquals("Edited board", reloaded.presentation().title());
        assertEquals(80, reloaded.presentation().layout().pages().getFirst().durationTicks());
        assertEquals(5, reloaded.presentation().layout().refresh().valueTicks());
        assertEquals("Inherited status", reloaded.presentation().layout().sections().get("status").getFirst().text());
        assertEquals("override", reloaded.objectives().belowName().conflict());
        assertEquals("subject.health", reloaded.objectives().belowName().value());
        MotdDoc ping = MotdDoc.parse("motd.json", presets.resolve("motd", read("motd.json")));
        assertEquals(200, ping.entries().getFirst().counts().maximumValue());
        assertEquals("sequence", ping.rotation().mode());
        assertEquals("https://example.org/rules", ping.serverLinks().links().getFirst().url());
        assertEquals(fixture.get("presets").toString(), read("presets.json"));
    }

    @Test
    void behaviorUpgradeReportsMatchingChangesAndBacksUpOriginal() throws IOException {
        String source = "{\"schemaVersion\":1,\"revision\":7,\"on\":["
            + "{\"trigger\":\"chat\",\"pattern\":\"^hello$\",\"do\":[]}]}";
        write("behaviors/greeting.json", source);
        loader.loadForBoot();
        LegacyGlossDataImporter.Result result = importer().run();
        assertEquals(LegacyGlossDataImporter.Status.APPROXIMATED, status(result, "behaviors/greeting.json"));
        assertEquals(source, backedUp(result, "behaviors/greeting.json"));
        BehaviorDoc current = BehaviorDoc.parse("greeting.json", read("behaviors/greeting.json"));
        assertEquals(2, current.schemaVersion());
        assertEquals(8, current.revision());
        assertEquals("^hello$", current.on().getFirst().pattern());
    }

    @Test
    void incompatibleBehaviorPatternIsReportedAndOriginalRetained() throws IOException {
        String source = "{\"schemaVersion\":1,\"revision\":1,\"on\":["
            + "{\"trigger\":\"chat\",\"pattern\":\"(?=a)a\",\"do\":[]}]}";
        write("behaviors/greeting.json", source);
        loader.loadForBoot();
        LegacyGlossDataImporter.Result result = importer().run();
        assertEquals(LegacyGlossDataImporter.Status.UNSUPPORTED, status(result, "behaviors/greeting.json"));
        assertEquals(source, read("behaviors/greeting.json"));
    }

    @Test
    void hologramMigratesToTheAnchorEnvelopeAndDropsTheEmbeddedId() throws IOException {
        write("holograms/spawn.json", LEGACY_HOLOGRAM);
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run();

        assertEquals(LegacyGlossDataImporter.Status.MIGRATED, status(result, "holograms/spawn.json"));
        HologramDoc expected = new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION,
            new HologramDoc.Anchor("world", new Vector(10.5D, 70.0D, -4.25D)),
            List.of("&aWelcome", "&7Second line"), null, null, 0.0D, 0.0D, List.of(), ShowCondition.ALWAYS, null, null);
        assertEquals(document(expected), read("holograms/spawn.json"));
        assertFalse(read("holograms/spawn.json").contains("\"id\""));
        assertEquals(LEGACY_HOLOGRAM, backedUp(result, "holograms/spawn.json"));
    }

    @Test
    void emojiMigratesAndClearsTheLegacyNoTriggerSentinel() throws IOException {
        write("emoji/heart.json", LEGACY_EMOJI_HEART);
        write("emoji/airplane.json", LEGACY_EMOJI_NO_TRIGGER);
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run();

        assertEquals(LegacyGlossDataImporter.Status.MIGRATED, status(result, "emoji/heart.json"));
        assertEquals(document(new EmojiDoc(EmojiDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION,
            "<3", "U+2764;", true, ShowCondition.ALWAYS)), read("emoji/heart.json"));
        assertEquals(document(new EmojiDoc(EmojiDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION,
            "", "U+2708;", true, ShowCondition.ALWAYS)), read("emoji/airplane.json"));
    }

    @Test
    void animationMigratesModeAndFrameInterval() throws IOException {
        write("animations/title.json", LEGACY_ANIMATION);
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run();

        assertEquals(LegacyGlossDataImporter.Status.MIGRATED, status(result, "animations/title.json"));
        AnimationDoc expected = new AnimationDoc(AnimationDoc.CURRENT_SCHEMA_VERSION,
            DocumentEnvelope.INITIAL_REVISION, "ascend", 500L, List.of("&cOne", "&6Two"), ShowCondition.ALWAYS);
        assertEquals(document(expected), read("animations/title.json"));
    }

    @Test
    void envelopePresentFilesSkipUntouched() throws IOException {
        String modern = document(new EmojiDoc(EmojiDoc.CURRENT_SCHEMA_VERSION, 7L, ":)", "U+263A;", true, ShowCondition.ALWAYS));
        write("emoji/modern.json", modern);
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run();

        assertEquals(LegacyGlossDataImporter.Status.SKIPPED_ENVELOPE, status(result, "emoji/modern.json"));
        assertEquals(modern, read("emoji/modern.json"));
        assertNull(result.backupPath(), "an all-v2 folder must not create a backup directory");
    }

    @Test
    void secondRunIsIdempotentByConstruction() throws IOException {
        write("holograms/spawn.json", LEGACY_HOLOGRAM);
        GlossConfigFile config = loader.loadForBoot();
        LegacyGlossDataImporter importer = importer();
        importer.run();
        String hologramAfterFirst = read("holograms/spawn.json");

        LegacyGlossDataImporter.Result rerun = importer.run();

        assertEquals(LegacyGlossDataImporter.Status.SKIPPED_ENVELOPE, status(rerun, "holograms/spawn.json"));
        assertEquals(hologramAfterFirst, read("holograms/spawn.json"));
        assertEquals(0, rerun.count(LegacyGlossDataImporter.Status.MIGRATED));
        assertNull(rerun.backupPath());
    }

    @Test
    void legacyConfigYmlOverlaysMechanicsAndMovesContentIntoDocuments() throws IOException {
        write("config.yml", """
            splash-screen: false
            features:
              chat-bubbles: false
            hotload:
              watch-interval-ticks: 20
            holograms:
              stack-distance: 0.5
            tablist:
              use-header-footers: false
              header: "&5Legacy Header"
              footer: "&7Legacy Footer"
              update-interval-ticks: 80
              group-list-names: false
            chat-bubbles:
              follow-players: false
              hide-own-messages: false
              word-wrap-break-chars: 40
              max-time-alive: 7000
              line-stagger-ticks: 3
              fly-away: false
              message:
                prefix: "&b"
                offset:
                  x: 0.0
                  y: 1.5
                  z: 0.0
              blacklist-worlds:
                - spawnhub
            motd:
              enabled: true
              texts:
                - "&dLine one\\n&7Line two"
                - "&aSingle"
            drops:
              name-format: "&e{count}x {type}"
            """);
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run();

        config = loader.loadForReload();
        assertFalse(config.splashScreen);
        assertFalse(config.features.chatBubbles);
        assertTrue(config.features.motd);
        assertEquals(20, config.hotload.watchIntervalTicks);
        assertEquals(80, config.tablist.updateIntervalTicks);
        TablistDoc tablist = TablistDoc.parse("tablist.json", read("tablist.json"));
        assertFalse(tablist.headerFooter().enabled());
        assertEquals("&5Legacy Header", tablist.headerFooter().presentation().header());
        assertEquals("&7Legacy Footer", tablist.headerFooter().presentation().footer());
        assertFalse(tablist.listNames().enabled());
        RealDropSettingsDoc realDrops = RealDropSettingsDoc.parse("default.json", read("real-drops/default.json"));
        assertEquals("&e{count}x {type}", realDrops.toConfig(true).labels().format());
        assertEquals(2L, realDrops.revision());

        String toml = read(GlossConfigLoader.FILE_NAME);

        BubbleStyleDoc bubbles = BubbleStyleDoc.parse("default.json", read("bubbles/default.json"));
        assertEquals("&b", bubbles.prefix());
        assertEquals(1.5D, bubbles.offset().getY());
        assertEquals(40, bubbles.wordWrapChars());
        assertEquals(7000L, bubbles.maxAliveMs());
        assertEquals("0", bubbles.motion().translation().y());
        assertFalse(bubbles.followPlayer());
        assertFalse(bubbles.hideOwn());
        assertEquals(List.of("spawnhub"), bubbles.blacklistWorlds());
        assertEquals(0.5D, bubbles.stackDistance());
        assertFalse(bubbles.shimmer().spawn());
        assertEquals(2L, bubbles.revision());

        MotdDoc motd = MotdDoc.parse("motd.json", read("motd.json"));
        assertEquals(2, motd.entries().size());
        assertEquals(List.of("&dLine one", "&7Line two"), motd.entries().get(0).lines());
        assertEquals(List.of("&aSingle"), motd.entries().get(1).lines());

        assertTrue(Files.exists(dataFolder.resolve("config.yml")));
        assertTrue(read("config.yml").contains("splash-screen: false"));
        assertTrue(Files.isRegularFile(dataFolder.resolve(LegacyGlossDataImporter.RECEIPT_FILE_NAME)));
        assertTrue(result.count(LegacyGlossDataImporter.Status.OVERLAID) > 0);
    }

    @Test
    void customizedBubbleStyleBlocksConfigYmlBubbleContent() throws IOException {
        write("bubbles/default.json", document(new BubbleStyleDoc(BubbleStyleDoc.CURRENT_SCHEMA_VERSION, 5L,
            "&d", new Vector(0.0D, 2.0D, 0.0D), 48, 9000L, true, false,
            BubbleStyleDoc.DEFAULTS.motion(), BubbleStyleDoc.DEFAULTS.shimmer(), null, List.of(), ShowCondition.ALWAYS, null, null, null, null, null, null)));
        write("config.yml", """
            chat-bubbles:
              message:
                prefix: "&b"
            """);
        String customized = read("bubbles/default.json");
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run();

        assertEquals(customized, read("bubbles/default.json"), "customized styles must not be overwritten");
        Optional<LegacyGlossDataImporter.Entry> note = result.entries().stream()
            .filter(entry -> entry.status() == LegacyGlossDataImporter.Status.CONFLICT)
            .findFirst();
        assertTrue(note.isPresent());
        assertTrue(note.get().detail().contains("customized"));
    }

    @Test
    void customizedMotdBlocksConfigYmlMotdTexts() throws IOException {
        write("motd.json", document(new MotdDoc(MotdDoc.CURRENT_SCHEMA_VERSION, 4L, ShowCondition.ALWAYS, null,
            List.of(MotdDoc.MotdEntry.ofLines(List.of("&bOperator MOTD"))), List.of(), null, null, null, null)));
        write("config.yml", """
            motd:
              texts:
                - "&dLegacy"
            """);
        String customized = read("motd.json");
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run();

        assertEquals(customized, read("motd.json"));
        assertEquals(1, result.count(LegacyGlossDataImporter.Status.CONFLICT));
    }

    @Test
    void brokenJsonPreventsTheWholeImportAndPreservesEverySource() throws IOException {
        write("emoji/broken.json", "{nope");
        write("emoji/heart.json", LEGACY_EMOJI_HEART);
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run();

        assertEquals(LegacyGlossDataImporter.Status.ERROR, status(result, "emoji/broken.json"));
        assertEquals("{nope", read("emoji/broken.json"));
        assertEquals(LegacyGlossDataImporter.Status.SKIPPED_NOTE, status(result, "emoji/heart.json"));
        assertEquals(LEGACY_EMOJI_HEART, read("emoji/heart.json"));
    }

    @Test
    void emptyDataFolderProducesNoEntriesAndNoBackup() throws IOException {
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run();

        assertTrue(result.entries().isEmpty());
        assertNull(result.backupPath());
        assertFalse(Files.exists(dataFolder.resolve("editor-sync-backups")));
    }

    @Test
    void previewDoesNotChangeDocumentsConfigurationOrCreateReceipts() throws IOException {
        write("emoji/heart.json", LEGACY_EMOJI_HEART);
        write("config.yml", "splash-screen: false\n");
        loader.loadForBoot();
        String originalConfig = read("gloss.toml");

        LegacyGlossDataImporter.Plan plan = importer().preview();

        assertTrue(plan.ready());
        assertTrue(plan.targets().contains("emoji/heart.json"));
        assertEquals(LEGACY_EMOJI_HEART, read("emoji/heart.json"));
        assertEquals(originalConfig, read("gloss.toml"));
        assertFalse(Files.exists(dataFolder.resolve(LegacyGlossDataImporter.RECEIPT_FILE_NAME)));
        assertFalse(Files.exists(dataFolder.resolve("editor-sync-transactions")));
    }

    @Test
    void aConfigurationDestinationFailurePreventsEveryDocumentWrite() throws IOException {
        write("emoji/heart.json", LEGACY_EMOJI_HEART);
        write("config.yml", "splash-screen: false\n");
        Files.createDirectory(dataFolder.resolve("gloss.toml"));

        LegacyGlossDataImporter.Result result = importer().run();

        assertEquals(1, result.count(LegacyGlossDataImporter.Status.ERROR));
        assertEquals(LEGACY_EMOJI_HEART, read("emoji/heart.json"));
        assertEquals("splash-screen: false\n", read("config.yml"));
        assertFalse(Files.exists(dataFolder.resolve(LegacyGlossDataImporter.RECEIPT_FILE_NAME)));
    }

    @Test
    void aChangedSourceInvalidatesThePreviewWithoutOverwritingEdits() throws IOException {
        write("emoji/heart.json", LEGACY_EMOJI_HEART);
        loader.loadForBoot();
        LegacyGlossDataImporter importer = importer();
        LegacyGlossDataImporter.Plan plan = importer.preview();
        String changed = LEGACY_EMOJI_HEART.replace("<3", ":heart:");
        write("emoji/heart.json", changed);

        LegacyGlossDataImporter.Result result = importer.apply(plan);

        assertEquals(1, result.count(LegacyGlossDataImporter.Status.ERROR));
        assertEquals(changed, read("emoji/heart.json"));
        assertNull(result.backupPath());
    }

    @Test
    void configurationReceiptMakesRetriesIdempotentWithoutRenamingTheSource() throws IOException {
        write("config.yml", "splash-screen: false\n");
        loader.loadForBoot();
        LegacyGlossDataImporter importer = importer();
        assertEquals(0, importer.run().count(LegacyGlossDataImporter.Status.ERROR));
        GlossConfigFile changed = loader.loadForReload();
        changed.splashScreen = true;
        loader.save(changed);

        LegacyGlossDataImporter.Result repeated = importer.run();

        assertNull(repeated.backupPath());
        assertTrue(loader.loadForReload().splashScreen);
        assertEquals("splash-screen: false\n", read("config.yml"));
    }

    @Test
    void aChangedDestinationInvalidatesTheEntirePreview() throws IOException {
        write("emoji/heart.json", LEGACY_EMOJI_HEART);
        write("config.yml", "splash-screen: false\n");
        loader.loadForBoot();
        LegacyGlossDataImporter importer = importer();
        LegacyGlossDataImporter.Plan plan = importer.preview();
        GlossConfigFile changed = loader.loadForReload();
        changed.metrics = false;
        loader.save(changed);
        String expected = read("gloss.toml");

        LegacyGlossDataImporter.Result result = importer.apply(plan);

        assertEquals(1, result.count(LegacyGlossDataImporter.Status.ERROR));
        assertEquals(LEGACY_EMOJI_HEART, read("emoji/heart.json"));
        assertEquals(expected, read("gloss.toml"));
        assertFalse(Files.exists(dataFolder.resolve(LegacyGlossDataImporter.RECEIPT_FILE_NAME)));
    }

    @Test
    void historicalEnvelopeIsConvertedBackedUpAndOnlyRevisedOnce() throws IOException {
        String original;
        try (InputStream input = getClass().getResourceAsStream("/importer/versioned-gloss/holograms-v1.json")) {
            original = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        write("holograms/spawn.json", original);
        LegacyGlossDataImporter importer = importer();

        LegacyGlossDataImporter.Result result = importer.run();

        assertEquals(LegacyGlossDataImporter.Status.MIGRATED, status(result, "holograms/spawn.json"));
        HologramDoc migrated = HologramDoc.parse("spawn.json", read("holograms/spawn.json"));
        assertEquals(7L, migrated.revision());
        assertEquals(2.5F, migrated.style().scaleX());
        assertEquals(original, backedUp(result, "holograms/spawn.json"));
        assertNull(importer.run().backupPath());
        assertEquals(7L, HologramDoc.parse("spawn.json", read("holograms/spawn.json")).revision());
    }

    @Test
    void unsupportedEnvelopeBlocksTheEntireImport() throws IOException {
        write("emoji/heart.json", LEGACY_EMOJI_HEART);
        write("holograms/future.json", "{\"schemaVersion\":999,\"revision\":1}");

        LegacyGlossDataImporter.Result result = importer().run();

        assertEquals(1, result.count(LegacyGlossDataImporter.Status.UNSUPPORTED));
        assertEquals(LEGACY_EMOJI_HEART, read("emoji/heart.json"));
        assertNull(result.backupPath());
    }

    @Test
    void invalidCurrentProjectDocumentBlocksOtherwiseValidConversion() throws IOException {
        write("emoji/heart.json", LEGACY_EMOJI_HEART);
        write("motd.json", "{\"schemaVersion\":1,\"revision\":0}");

        LegacyGlossDataImporter.Result result = importer().run();

        assertEquals(1, result.count(LegacyGlossDataImporter.Status.ERROR));
        assertEquals(LEGACY_EMOJI_HEART, read("emoji/heart.json"));
        assertNull(result.backupPath());
    }

    @Test
    void addingADocumentAfterPreviewInvalidatesProjectValidation() throws IOException {
        write("emoji/heart.json", LEGACY_EMOJI_HEART);
        LegacyGlossDataImporter importer = importer();
        LegacyGlossDataImporter.Plan plan = importer.preview();
        write("motd.json", "{\"schemaVersion\":1,\"revision\":0}");

        LegacyGlossDataImporter.Result result = importer.apply(plan);

        assertEquals(1, result.count(LegacyGlossDataImporter.Status.ERROR));
        assertEquals(LEGACY_EMOJI_HEART, read("emoji/heart.json"));
    }

    @Test
    void legacyIndicatorSettingsRetainTextLimitsAndMotionRates() throws IOException {
        write("config.yml", """
            holograms:
              temporary-update-interval-ticks: 4
            damage-indicators:
              max-indicators-per-second: 12
              max-ms-alive: 2100
              damage-indicator-prefix: '&e'
              heal-indicator-prefix: '&b'
              decimals: 2
              show-heals: false
              motion:
                random-throw-force: 0.2
                initial-up-force: 0.3
                gravity-factor: 0.04
            """);

        LegacyGlossDataImporter.Result result = importer().run();

        assertEquals(0, result.count(LegacyGlossDataImporter.Status.ERROR));
        assertEquals(LegacyGlossDataImporter.Status.APPROXIMATED, status(result, "config.yml:damage-indicators"));
        DamageIndicatorSettingsDoc indicators = DamageIndicatorSettingsDoc.parse("default.json", read("damage-indicators/default.json"));
        assertEquals(12, indicators.limits().maxPerSecond());
        assertEquals(2100L, indicators.limits().lifetimeMs());
        assertEquals(2, indicators.limits().decimals());
        assertEquals("&e{amount}", indicators.damage().presentation().format());
        assertEquals("&b{amount}", indicators.healing().presentation().format());
        assertEquals("false", indicators.healing().when());
        assertEquals(1.0D, indicators.damage().presentation().motion().horizontalSpeed());
        assertEquals(1.5D, indicators.damage().presentation().motion().verticalSpeed());
        assertEquals(-1.0D, indicators.damage().presentation().motion().verticalAcceleration());
    }

    private static String document(Object doc) {
        return DocumentParsers.GSON.toJson(doc) + System.lineSeparator();
    }

    private String backedUp(LegacyGlossDataImporter.Result result, String relativePath) throws IOException {
        assertNotNull(result.backupPath(), "a migration must create a backup directory");
        Path backup = Path.of(result.backupPath()).resolve(relativePath);
        assertTrue(Files.isRegularFile(backup), "missing backup for " + relativePath);
        return Files.readString(backup, StandardCharsets.UTF_8);
    }

    private static LegacyGlossDataImporter.Status status(LegacyGlossDataImporter.Result result, String path) {
        Optional<LegacyGlossDataImporter.Entry> entry = result.entries().stream()
            .filter(candidate -> candidate.path().equals(path))
            .findFirst();
        assertTrue(entry.isPresent(), "missing entry for " + path + " in " + result.entries());
        return entry.get().status();
    }

    private void write(String relativePath, String content) throws IOException {
        Path path = dataFolder.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private String read(String relativePath) throws IOException {
        return Files.readString(dataFolder.resolve(relativePath), StandardCharsets.UTF_8);
    }
}
