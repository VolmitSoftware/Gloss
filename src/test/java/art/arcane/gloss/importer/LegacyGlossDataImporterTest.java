package art.arcane.gloss.importer;

import art.arcane.gloss.animation.AnimationDoc;
import art.arcane.gloss.board.BoardDoc;
import art.arcane.gloss.bubble.BubbleStyleDoc;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.config.GlossConfigLoader;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.emoji.EmojiDoc;
import art.arcane.gloss.hologram.HologramDoc;
import art.arcane.gloss.motd.MotdDoc;
import art.arcane.gloss.tab.TablistDoc;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
    private static final String LEGACY_BOARD = """
        {
          "title": "&6Main",
          "content": ["&fLine one", "&7Line two"],
          "primary": true,
          "permission": "vip.board"
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
        return new LegacyGlossDataImporter(dataFolder.toFile(), loader);
    }

    @Test
    void hologramMigratesToTheAnchorEnvelopeAndDropsTheEmbeddedId() throws IOException {
        write("holograms/spawn.json", LEGACY_HOLOGRAM);
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run(config);

        assertEquals(LegacyGlossDataImporter.Status.MIGRATED, status(result, "holograms/spawn.json"));
        HologramDoc expected = new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION,
            new HologramDoc.Anchor("world", new Vector(10.5D, 70.0D, -4.25D)),
            List.of("&aWelcome", "&7Second line"));
        assertEquals(document(expected), read("holograms/spawn.json"));
        assertFalse(read("holograms/spawn.json").contains("\"id\""));
        assertEquals(LEGACY_HOLOGRAM, backedUp(result, "holograms/spawn.json"));
    }

    @Test
    void boardMigratesContentToLinesWithEmptyGroups() throws IOException {
        write("boards/main.json", LEGACY_BOARD);
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run(config);

        assertEquals(LegacyGlossDataImporter.Status.MIGRATED, status(result, "boards/main.json"));
        BoardDoc expected = new BoardDoc(BoardDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION,
            "&6Main", List.of("&fLine one", "&7Line two"), true, false, "vip.board", List.of());
        assertEquals(document(expected), read("boards/main.json"));
        assertEquals(LEGACY_BOARD, backedUp(result, "boards/main.json"));
    }

    @Test
    void emojiMigratesAndClearsTheLegacyNoTriggerSentinel() throws IOException {
        write("emoji/heart.json", LEGACY_EMOJI_HEART);
        write("emoji/airplane.json", LEGACY_EMOJI_NO_TRIGGER);
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run(config);

        assertEquals(LegacyGlossDataImporter.Status.MIGRATED, status(result, "emoji/heart.json"));
        assertEquals(document(new EmojiDoc(EmojiDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION,
            "<3", "U+2764;", true)), read("emoji/heart.json"));
        assertEquals(document(new EmojiDoc(EmojiDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION,
            "", "U+2708;", true)), read("emoji/airplane.json"));
    }

    @Test
    void animationMigratesModeAndFrameInterval() throws IOException {
        write("animations/title.json", LEGACY_ANIMATION);
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run(config);

        assertEquals(LegacyGlossDataImporter.Status.MIGRATED, status(result, "animations/title.json"));
        AnimationDoc expected = new AnimationDoc(AnimationDoc.CURRENT_SCHEMA_VERSION,
            DocumentEnvelope.INITIAL_REVISION, "ascend", 500L, List.of("&cOne", "&6Two"));
        assertEquals(document(expected), read("animations/title.json"));
    }

    @Test
    void envelopePresentFilesSkipUntouched() throws IOException {
        String modern = document(new EmojiDoc(EmojiDoc.CURRENT_SCHEMA_VERSION, 7L, ":)", "U+263A;", true));
        write("emoji/modern.json", modern);
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run(config);

        assertEquals(LegacyGlossDataImporter.Status.SKIPPED_ENVELOPE, status(result, "emoji/modern.json"));
        assertEquals(modern, read("emoji/modern.json"));
        assertNull(result.backupPath(), "an all-v2 folder must not create a backup directory");
    }

    @Test
    void secondRunIsIdempotentByConstruction() throws IOException {
        write("holograms/spawn.json", LEGACY_HOLOGRAM);
        write("boards/main.json", LEGACY_BOARD);
        GlossConfigFile config = loader.loadForBoot();
        LegacyGlossDataImporter importer = importer();
        importer.run(config);
        String hologramAfterFirst = read("holograms/spawn.json");
        String boardAfterFirst = read("boards/main.json");

        LegacyGlossDataImporter.Result rerun = importer.run(config);

        assertEquals(LegacyGlossDataImporter.Status.SKIPPED_ENVELOPE, status(rerun, "holograms/spawn.json"));
        assertEquals(LegacyGlossDataImporter.Status.SKIPPED_ENVELOPE, status(rerun, "boards/main.json"));
        assertEquals(hologramAfterFirst, read("holograms/spawn.json"));
        assertEquals(boardAfterFirst, read("boards/main.json"));
        assertEquals(0, rerun.count(LegacyGlossDataImporter.Status.MIGRATED));
        assertNull(rerun.backupPath());
    }

    @Test
    void groupsAbsorbIntoTablistFormatsAndBoardGroupsThenMoveToTheBackup() throws IOException {
        write("boards/main.json", LEGACY_BOARD);
        write("groups/vip.yml", "tablist-name: \"&6[VIP] $player\"\ndefault-board: \"main\"\n");
        write("groups/_op.yml", "tablist-name: \"&6$player\"\n");
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run(config);

        assertEquals(LegacyGlossDataImporter.Status.ABSORBED, status(result, "groups/vip.yml"));
        assertEquals(LegacyGlossDataImporter.Status.ABSORBED, status(result, "groups/_op.yml"));

        TablistDoc tablist = TablistDoc.parse("tablist.json", read("tablist.json"));
        assertEquals(2L, tablist.revision(), "tablist merge must bump the revision");
        assertEquals("&6[VIP] $player", tablist.nameFormats().get("vip"));
        assertEquals("&6$player", tablist.nameFormats().get("_op"));
        assertEquals("$player", tablist.nameFormats().get("default"), "shipped formats must be preserved");
        assertTrue(tablist.useHeaderFooter(), "shipped tablist fields must be preserved");

        BoardDoc board = BoardDoc.parse("main.json", read("boards/main.json"));
        assertEquals(List.of("vip"), board.groups());
        assertEquals(2L, board.revision(), "board group append must bump the revision");

        assertFalse(Files.exists(dataFolder.resolve("groups")), "groups/ must be fully absorbed");
        assertNotNull(result.backupPath());
        assertTrue(Files.isRegularFile(Path.of(result.backupPath()).resolve("groups/vip.yml")));
    }

    @Test
    void groupWithMissingDefaultBoardIsNotedAndStillAbsorbed() throws IOException {
        write("groups/vip.yml", "default-board: \"nosuchboard\"\n");
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run(config);

        assertEquals(1, result.count(LegacyGlossDataImporter.Status.SKIPPED_NOTE));
        assertEquals(LegacyGlossDataImporter.Status.ABSORBED, status(result, "groups/vip.yml"));
        assertFalse(Files.exists(dataFolder.resolve("groups")));
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

        LegacyGlossDataImporter.Result result = importer().run(config);

        assertFalse(config.splashScreen);
        assertFalse(config.features.chatBubbles);
        assertTrue(config.features.motd);
        assertEquals(20, config.hotload.watchIntervalTicks);
        assertEquals(0.5D, config.holograms.stackDistance);
        assertEquals(80, config.tablist.updateIntervalTicks);
        assertEquals(List.of("spawnhub"), config.chatBubbles.blacklistWorlds);
        assertEquals("&e{count}x {type}", config.drops.nameFormat);

        String toml = read(GlossConfigLoader.FILE_NAME);
        assertTrue(toml.contains("stackDistance = 0.5"), "overlay must re-serialize config.toml");

        TablistDoc tablist = TablistDoc.parse("tablist.json", read("tablist.json"));
        assertFalse(tablist.useHeaderFooter());
        assertEquals("&5Legacy Header", tablist.header());
        assertEquals("&7Legacy Footer", tablist.footer());
        assertFalse(tablist.groupListNames());
        assertEquals(2L, tablist.revision());

        BubbleStyleDoc bubbles = BubbleStyleDoc.parse("default.json", read("bubbles/default.json"));
        assertEquals("&b", bubbles.prefix());
        assertEquals(1.5D, bubbles.offset().getY());
        assertEquals(40, bubbles.wordWrapChars());
        assertEquals(7000L, bubbles.maxAliveMs());
        assertEquals("0", bubbles.motion().translation().y());
        assertFalse(bubbles.followPlayer());
        assertFalse(bubbles.hideOwn());
        assertEquals(2L, bubbles.revision());

        MotdDoc motd = MotdDoc.parse("motd.json", read("motd.json"));
        assertEquals(2, motd.entries().size());
        assertEquals(List.of("&dLine one", "&7Line two"), motd.entries().get(0).lines());
        assertEquals(List.of("&aSingle"), motd.entries().get(1).lines());

        assertFalse(Files.exists(dataFolder.resolve("config.yml")));
        assertTrue(read("config.yml.imported").contains("splash-screen: false"));
        assertTrue(result.count(LegacyGlossDataImporter.Status.OVERLAID) > 0);
    }

    @Test
    void customizedBubbleStyleBlocksConfigYmlBubbleContent() throws IOException {
        write("bubbles/default.json", document(new BubbleStyleDoc(BubbleStyleDoc.CURRENT_SCHEMA_VERSION, 5L,
            "&d", new Vector(0.0D, 2.0D, 0.0D), 48, 9000L, true, false,
            BubbleStyleDoc.DEFAULTS.motion(), BubbleStyleDoc.DEFAULTS.shimmer(), null)));
        write("config.yml", """
            chat-bubbles:
              message:
                prefix: "&b"
            """);
        String customized = read("bubbles/default.json");
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run(config);

        assertEquals(customized, read("bubbles/default.json"), "customized styles must not be overwritten");
        Optional<LegacyGlossDataImporter.Entry> note = result.entries().stream()
            .filter(entry -> entry.status() == LegacyGlossDataImporter.Status.SKIPPED_NOTE)
            .findFirst();
        assertTrue(note.isPresent());
        assertTrue(note.get().detail().contains("customized"));
    }

    @Test
    void customizedMotdBlocksConfigYmlMotdTexts() throws IOException {
        write("motd.json", document(new MotdDoc(MotdDoc.CURRENT_SCHEMA_VERSION, 4L,
            List.of(new MotdDoc.MotdEntry(List.of("&bOperator MOTD"))))));
        write("config.yml", """
            motd:
              texts:
                - "&dLegacy"
            """);
        String customized = read("motd.json");
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run(config);

        assertEquals(customized, read("motd.json"));
        assertEquals(1, result.count(LegacyGlossDataImporter.Status.SKIPPED_NOTE));
    }

    @Test
    void brokenJsonRecordsAnErrorWhileOtherFilesProceedAndTheSourceStaysUntouched() throws IOException {
        write("emoji/broken.json", "{nope");
        write("emoji/heart.json", LEGACY_EMOJI_HEART);
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run(config);

        assertEquals(LegacyGlossDataImporter.Status.ERROR, status(result, "emoji/broken.json"));
        assertEquals("{nope", read("emoji/broken.json"));
        assertEquals(LegacyGlossDataImporter.Status.MIGRATED, status(result, "emoji/heart.json"));
    }

    @Test
    void emptyDataFolderProducesNoEntriesAndNoBackup() throws IOException {
        GlossConfigFile config = loader.loadForBoot();

        LegacyGlossDataImporter.Result result = importer().run(config);

        assertTrue(result.entries().isEmpty());
        assertNull(result.backupPath());
        assertFalse(Files.exists(dataFolder.resolve(LegacyGlossDataImporter.BACKUP_DIRECTORY_NAME)));
    }

    private static String document(Object doc) {
        return BukkitJson.GSON.toJson(doc) + System.lineSeparator();
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
