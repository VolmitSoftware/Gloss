package art.arcane.gloss.doc;

import art.arcane.gloss.animation.AnimationDoc;
import art.arcane.gloss.board.BoardDoc;
import art.arcane.gloss.bubble.BubbleStyleDoc;
import art.arcane.gloss.emoji.EmojiDoc;
import art.arcane.gloss.hologram.HologramDoc;
import art.arcane.gloss.motd.MotdDoc;
import art.arcane.gloss.panel.PanelDefinition;
import art.arcane.gloss.panel.PanelRepository;
import art.arcane.gloss.panel.PanelTransform;
import art.arcane.gloss.persistence.GlossProjectTransaction;
import art.arcane.gloss.preview.doc.PreviewDocumentRegistry;
import art.arcane.gloss.tab.TablistDoc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gloss never creates a folder it has nothing to put in. Every data folder is made by the first
 * write that needs it, so an operator who never uses a feature never sees its directory.
 *
 * <p>The suite drives the headless half of the boot path — document registries, shipped-default
 * extraction, the panel store, the editor-sync transaction log and the preview registry — against a
 * fresh data folder, then walks the result. {@code MenuCatalog} is not constructed here because
 * its constructor needs a live plugin; {@code MenuDocumentRepositoryTest} covers the {@code menus/}
 * half of the same contract.
 */
class LazyDataFolderTest {

    @TempDir
    File dataFolder;

    @Test
    void freshBootLeavesNoEmptyDirectoryInTheDataFolder() throws IOException {
        bootDefaultConfiguration();

        assertEquals(List.of(), emptyDirectories());
    }

    @Test
    void freshBootDoesNotCreateFoldersForContentThatDoesNotExistYet() throws IOException {
        bootDefaultConfiguration();

        assertMissing("menus");
        assertMissing("images");
        assertMissing("panels");
        assertMissing(HologramDoc.KIND);
        assertMissing("editor-sync-transactions");
        assertMissing("editor-sync-backups");
        assertMissing("import-backups");
    }

    @Test
    void freshBootStillExtractsTheShippedDefaultsOfEnabledFeatures() throws IOException {
        bootDefaultConfiguration();

        assertPopulated(BoardDoc.KIND);
        assertPopulated(EmojiDoc.KIND);
        assertPopulated(AnimationDoc.KIND);
        assertPopulated(BubbleStyleDoc.KIND);
        assertPopulated("previews");
        assertTrue(new File(dataFolder, TablistDoc.KIND + ".json").isFile());
        assertFalse(new File(dataFolder, MotdDoc.KIND + ".json").exists());
    }

    /**
     * Boards follow their toggle like every other shipped default: with the sidebar off nothing is
     * written, and switching it on later extracts without a restart.
     */
    @Test
    void boardDefaultsWaitForTheSidebarToggle() throws IOException {
        File boards = new File(dataFolder, BoardDoc.KIND);

        extractAndReload(ShippedDocumentCatalog.BOARDS, false);

        assertMissing(BoardDoc.KIND);
        assertFalse(new File(boards, "default.json").exists());

        extractAndReload(ShippedDocumentCatalog.BOARDS, true);

        assertPopulated(BoardDoc.KIND);
        assertTrue(new File(boards, "default.json").isFile());
    }

    @Test
    void documentRegistryReloadDoesNotCreateItsFolder() {
        File folder = new File(dataFolder, HologramDoc.KIND);
        DocumentRegistry<String> registry = DocumentRegistry.folder(HologramDoc.KIND, folder,
            (fileName, raw) -> raw, value -> 1L);

        registry.reload();

        assertFalse(folder.exists());
        assertTrue(registry.ids().isEmpty());
    }

    @Test
    void documentRegistryLoadsAFolderThatAppearsAfterTheReload() throws IOException {
        File folder = new File(dataFolder, HologramDoc.KIND);
        DocumentRegistry<String> registry = DocumentRegistry.folder(HologramDoc.KIND, folder,
            (fileName, raw) -> raw.trim(), value -> 1L);
        registry.reload();

        assertTrue(folder.mkdirs());
        Files.writeString(new File(folder, "spawn.json").toPath(), "lines", StandardCharsets.UTF_8);

        DocumentDelta delta = registry.poll();
        assertEquals(List.of("spawn"), delta.loaded());
        assertTrue(registry.acknowledge(delta));
        assertEquals("lines", registry.get("spawn").value());
    }

    @Test
    void documentStoreCreatesItsFolderOnTheFirstWrite() throws IOException {
        File folder = new File(dataFolder, HologramDoc.KIND);
        DocumentStore<StoredDoc> store = new DocumentStore<>(HologramDoc.KIND, folder,
            new DocumentReviser<>() {
                @Override
                public long revisionOf(StoredDoc value) {
                    return value.revision();
                }

                @Override
                public StoredDoc withRevision(StoredDoc value, long revision) {
                    return new StoredDoc(revision);
                }
            });
        assertFalse(folder.exists());

        store.write("spawn", new StoredDoc(1L));

        assertTrue(new File(folder, "spawn.json").isFile());
    }

    @Test
    void shippedDefaultsCreateTheFolderOnlyWhenADocumentIsWritten() {
        File emptyKind = new File(dataFolder, "unshipped");
        new ShippedDefaults("unshipped", emptyKind, List.of()).extractMissing();
        assertFalse(emptyKind.exists());

        File boards = new File(dataFolder, BoardDoc.KIND);
        List<String> written = new ShippedDefaults(BoardDoc.KIND, boards,
            ShippedDocumentCatalog.BOARDS.names()).extractMissing();

        assertEquals(ShippedDocumentCatalog.BOARDS.names(), written);
        assertTrue(new File(boards, "default.json").isFile());
    }

    @Test
    void panelRepositoryLoadDoesNotCreateItsFolderButCreateDoes() throws IOException {
        PanelRepository repository = new PanelRepository(dataFolder);

        assertEquals(0, repository.load().loaded());
        assertFalse(Files.exists(repository.directory()));

        repository.create(PanelDefinition.create("spawn", "spawn/menu",
            PanelTransform.at("minecraft:overworld", UUID.randomUUID(), 1.0D, 64.0D, 2.0D, 0.0D)));

        assertTrue(Files.isRegularFile(repository.directory().resolve("spawn.json")));
    }

    @Test
    void projectTransactionRecoveryDoesNotCreateItsRoots() throws IOException {
        new GlossProjectTransaction(dataFolder.toPath()).recover();

        assertMissing("editor-sync-transactions");
        assertMissing("editor-sync-backups");
    }

    @Test
    void projectTransactionArchivesNoBackupFolderForAnAllNewPublication() throws IOException {
        Path data = dataFolder.toPath();
        Path menu = data.resolve("menus/new.json");
        GlossProjectTransaction transaction = new GlossProjectTransaction(data);
        Map<Path, byte[]> expectedAbsent = new HashMap<>();
        expectedAbsent.put(menu, null);

        GlossProjectTransaction.Pending pending = transaction.apply("session",
            Map.of("new", "{\"components\":[]}"), Map.of(), null, expectedAbsent);
        assertFalse(Files.exists(pending.transactionDirectory().resolve("backup")));

        transaction.commit(pending);

        Path archived = data.resolve("editor-sync-backups").resolve(pending.id());
        assertTrue(Files.isDirectory(archived));
        assertFalse(Files.exists(archived.resolve("backup")));
    }

    /**
     * A commit publishes by moving each staged file out of {@code stage/}, so the directories that
     * held them are empty by the time the transaction is archived. The archive keeps the journal,
     * not the skeleton.
     */
    @Test
    void projectTransactionArchivesNoEmptyStageSkeletonAfterCommit() throws IOException {
        Path data = dataFolder.toPath();
        Path menu = data.resolve("menus/deep/new.json");
        GlossProjectTransaction transaction = new GlossProjectTransaction(data);
        Map<Path, byte[]> expectedAbsent = new HashMap<>();
        expectedAbsent.put(menu, null);

        GlossProjectTransaction.Pending pending = transaction.apply("session",
            Map.of("deep/new", "{\"components\":[]}"), Map.of(), null, expectedAbsent);
        assertTrue(Files.isDirectory(pending.transactionDirectory().resolve("stage/menus/deep")));

        transaction.commit(pending);

        Path archived = data.resolve("editor-sync-backups").resolve(pending.id());
        assertTrue(Files.isRegularFile(archived.resolve("journal.json")));
        assertTrue(Files.isRegularFile(menu));
        assertFalse(Files.exists(archived.resolve("stage")));
        assertEquals(List.of(), emptyDirectoriesBelow(archived));
    }

    @Test
    void projectTransactionKeepsTheBackupFolderWhenSomethingWasOverwritten() throws IOException {
        Path data = dataFolder.toPath();
        Path menu = data.resolve("menus/shop.json");
        Files.createDirectories(menu.getParent());
        byte[] original = "{\"components\":[]}".getBytes(StandardCharsets.UTF_8);
        Files.write(menu, original);
        GlossProjectTransaction transaction = new GlossProjectTransaction(data);

        GlossProjectTransaction.Pending pending = transaction.apply("session",
            Map.of("shop", "{\"offset\":[0,0,1],\"components\":[]}"), Map.of(), null,
            Map.of(menu, original));

        assertTrue(Files.isRegularFile(pending.transactionDirectory()
            .resolve("backup").resolve("menus").resolve("shop.json")));
    }

    /**
     * The headless half of a first boot on the shipped defaults: previews, boards, emoji, animations
     * and bubbles are on, motd is off.
     */
    private void bootDefaultConfiguration() throws IOException {
        new GlossProjectTransaction(dataFolder.toPath()).recover();
        new PanelRepository(dataFolder).load();
        extractAndReload(ShippedDocumentCatalog.BOARDS, true);
        extractAndReload(ShippedDocumentCatalog.EMOJI, true);
        extractAndReload(ShippedDocumentCatalog.ANIMATIONS, true);
        extractAndReload(ShippedDocumentCatalog.BUBBLES, true);
        extractSingleton(ShippedDocumentCatalog.TABLIST, true);
        extractSingleton(ShippedDocumentCatalog.MOTD, false);
        DocumentRegistry.folder(HologramDoc.KIND, new File(dataFolder, HologramDoc.KIND),
            HologramDoc::parse, HologramDoc::revision).reload();
        new PreviewDocumentRegistry(dataFolder);
    }

    private void extractAndReload(ShippedDocumentCatalog.Entry<?> entry, boolean enabled) {
        File folder = new File(dataFolder, entry.kind());
        if (enabled) {
            new ShippedDefaults(entry.kind(), folder, entry.names()).extractMissing();
        }
        DocumentRegistry.folder(entry.kind(), folder, entry.parser(), value -> 1L).reload();
    }

    private void extractSingleton(ShippedDocumentCatalog.Entry<?> entry, boolean enabled) {
        if (enabled) {
            new ShippedDefaults(entry.kind(), dataFolder, entry.names()).extractMissing();
        }
        DocumentRegistry.singleFile(entry.kind(), new File(dataFolder, entry.kind() + ".json"),
            entry.parser(), value -> 1L).reload();
    }

    private List<String> emptyDirectories() throws IOException {
        return emptyDirectoriesBelow(dataFolder.toPath());
    }

    private List<String> emptyDirectoriesBelow(Path root) throws IOException {
        List<String> empty = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (path.equals(root) || !Files.isDirectory(path)) {
                    continue;
                }
                try (Stream<Path> children = Files.list(path)) {
                    if (children.findAny().isEmpty()) {
                        empty.add(root.relativize(path).toString());
                    }
                }
            }
        }
        empty.sort(String::compareTo);
        return empty;
    }

    private void assertMissing(String name) {
        assertFalse(new File(dataFolder, name).exists(), name + " should not have been created");
    }

    private void assertPopulated(String name) throws IOException {
        File folder = new File(dataFolder, name);
        assertTrue(folder.isDirectory(), name + " should have been created");
        try (Stream<Path> children = Files.list(folder.toPath())) {
            assertTrue(children.findAny().isPresent(), name + " should not be empty");
        }
    }

    private record StoredDoc(long revision) {
    }
}
