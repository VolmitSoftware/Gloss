package art.arcane.gloss.importer;

import art.arcane.gloss.GlossConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreparedImportTest {
    @TempDir
    Path root;

    @Test
    void rejectsOversizedFilesBeforeRetainingTheirBytes() throws IOException {
        Path source = root.resolve("source.json");
        Files.write(source, new byte[17]);
        PreparedImport preparation = new PreparedImport(root, new GlossConfig.Imports(16, 32, 4, 600, 8, 32));
        assertThrows(IOException.class, () -> preparation.read(source));
        Files.write(source, new byte[16]);
        assertEquals(16, preparation.read(source).length);
    }

    @Test
    void boundsRetainedSourcesAndStagedReplacementsTogether() throws IOException {
        Path source = root.resolve("source.json");
        Path replacement = root.resolve("replacement.json");
        Files.write(source, new byte[12]);
        PreparedImport preparation = new PreparedImport(root, new GlossConfig.Imports(16, 20, 4, 600, 8, 20));
        preparation.read(source);
        assertThrows(IOException.class, () -> preparation.stage(replacement, new byte[9]));
        preparation.stage(replacement, new byte[8]);
        preparation.stage(replacement, new byte[4]);
        preparation.stage(root.resolve("another.json"), new byte[4]);
        assertEquals(2, preparation.targets().size());
    }

    @Test
    void boundedPathsIncludeMissingDestinationsAndReadsAreImmutable() throws IOException {
        Path source = root.resolve("source.json");
        Files.write(source, new byte[]{1, 2});
        PreparedImport preparation = new PreparedImport(root, new GlossConfig.Imports(16, 32, 2, 600, 8, 32));
        byte[] copy = preparation.read(source);
        copy[0] = 9;
        preparation.read(root.resolve("missing.json"));
        assertThrows(IOException.class, () -> preparation.read(root.resolve("third.json")));
        assertArrayEquals(new byte[]{1, 2}, preparation.read(source));
    }

    @Test
    void validatesTheCompleteStagedPresetCatalogRegardlessOfFileOrder() throws IOException {
        PreparedImport preparation = new PreparedImport(root, new GlossConfig.Imports(4096, 16384, 16, 600, 8, 16384));
        preparation.document(root.resolve("boards/board.json"), bytes("""
            {"schemaVersion":2,"revision":1,"preset":"base"}
            """));
        preparation.document(root.resolve("presets.json"), bytes("""
            {"schemaVersion":1,"revision":1,"presets":{"boards":{"base":{"values":{
              "presentation":{"title":"Preset","lines":["Row"]}
            }}}}}
            """));
        preparation.validateDocuments();
        assertEquals(2, preparation.targets().size());
    }

    @Test
    void visitedEntryLimitIncludesCollectionRootsDirectoriesAndIgnoredFiles() throws IOException {
        Path menus = Files.createDirectories(root.resolve("menus/nested")).getParent();
        for (String name : new String[]{"notes.txt", ".hidden.json", "~backup.json", "#draft.json"}) {
            Files.writeString(menus.resolve(name), "ignored");
        }
        PreparedImport bounded = new PreparedImport(root, limits(1, 5, 1));
        IOException failure = assertThrows(IOException.class, bounded::validateDocuments);
        assertTrue(failure.getMessage().contains("visited-entry limit"));
        new PreparedImport(root, limits(1, 6, 1)).validateDocuments();
    }

    @Test
    void traversalBudgetIsSharedAcrossCollectionsAndSingletons() throws IOException {
        Files.createDirectories(root.resolve("menus"));
        Files.createDirectories(root.resolve("boards"));
        Files.writeString(root.resolve("presets.json"), """
            {"schemaVersion":1,"revision":1}
            """);
        PreparedImport bounded = new PreparedImport(root, limits(16, 2, 1));
        IOException failure = assertThrows(IOException.class, bounded::validateDocuments);
        assertTrue(failure.getMessage().contains("visited-entry limit"));
        new PreparedImport(root, limits(16, 3, 1)).validateDocuments();
    }

    @Test
    void depthLimitAcceptsDeepestFilesButRejectsDeeperDirectories() throws IOException {
        Path nested = Files.createDirectories(root.resolve("menus/nested"));
        Files.writeString(nested.resolve("menu.json"), """
            {"components":[]}
            """);
        new PreparedImport(root, limits(16, 32, 1)).validateDocuments();
        Files.createDirectory(nested.resolve("too-deep"));
        PreparedImport bounded = new PreparedImport(root, limits(16, 32, 1));
        IOException failure = assertThrows(IOException.class, bounded::validateDocuments);
        assertTrue(failure.getMessage().contains("depth limit"));
    }

    @Test
    void stagedRecursiveDocumentsCannotExceedDepthBeforeDirectoriesExist() throws IOException {
        PreparedImport preparation = new PreparedImport(root, limits(16, 32, 1));
        byte[] content = bytes("""
            {"components":[]}
            """);
        IOException failure = assertThrows(IOException.class,
            () -> preparation.document(root.resolve("menus/one/two/menu.json"), content));
        assertTrue(failure.getMessage().contains("depth limit"));
        assertTrue(preparation.targets().isEmpty());
        preparation.document(root.resolve("menus/one/menu.json"), content);
        preparation.validateDocuments();

        PreparedImport staged = new PreparedImport(root, limits(16, 32, 1));
        staged.validateDocuments();
        IOException stagedFailure = assertThrows(IOException.class,
            () -> staged.stage(root.resolve("menus/one/two/menu.json"), content));
        assertTrue(stagedFailure.getMessage().contains("depth limit"));
        assertTrue(staged.targets().isEmpty());
    }

    @Test
    void flatCollectionsDoNotTraverseNestedDirectories() throws IOException {
        Path nested = Files.createDirectories(root.resolve("boards/ignored/deeper"));
        Files.writeString(nested.resolve("invalid.json"), "invalid");
        new PreparedImport(root, limits(1, 2, 1)).validateDocuments();
    }

    @Test
    void directorySymlinksCountAsEntriesWithoutFollowingTheirTargets() throws IOException {
        Path target = Files.createDirectories(root.resolve("outside/deep/nested"));
        Files.writeString(target.resolve("invalid.json"), "invalid");
        Path menus = Files.createDirectory(root.resolve("menus"));
        Files.createSymbolicLink(menus.resolve("linked"), target.getParent());
        new PreparedImport(root, limits(1, 2, 1)).validateDocuments();
        PreparedImport bounded = new PreparedImport(root, limits(1, 1, 1));
        IOException failure = assertThrows(IOException.class, bounded::validateDocuments);
        assertTrue(failure.getMessage().contains("visited-entry limit"));
    }

    @Test
    void jsonSymlinksAreRejectedWithoutReadingTheirTargets() throws IOException {
        Path menus = Files.createDirectory(root.resolve("menus"));
        Files.createSymbolicLink(menus.resolve("menu.json"), root.resolve("absent.json"));
        PreparedImport preparation = new PreparedImport(root, limits(16, 32, 1));
        IOException failure = assertThrows(IOException.class, preparation::validateDocuments);
        assertTrue(failure.getMessage().contains("regular file"));
    }

    @Test
    void jsonDirectoriesAreRejectedAsNonregularDocuments() throws IOException {
        Files.createDirectories(root.resolve("menus/not-a-file.json"));
        PreparedImport preparation = new PreparedImport(root, limits(16, 32, 1));
        IOException failure = assertThrows(IOException.class, preparation::validateDocuments);
        assertTrue(failure.getMessage().contains("regular file"));
    }

    @Test
    void singletonDocumentsRespectTheDocumentCountLimit() throws IOException {
        Files.writeString(root.resolve("names.json"), "{}");
        Files.writeString(root.resolve("presets.json"), "{}");
        PreparedImport preparation = new PreparedImport(root, limits(1, 32, 1));
        IOException failure = assertThrows(IOException.class, preparation::validateDocuments);
        assertTrue(failure.getMessage().contains("file-count limit"));
    }

    private static GlossConfig.Imports limits(int files, int visitedEntries, int depth) {
        return new GlossConfig.Imports(4096, 16384, files, 600, 8, 16384, visitedEntries, depth);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
