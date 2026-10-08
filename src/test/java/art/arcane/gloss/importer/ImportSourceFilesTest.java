package art.arcane.gloss.importer;

import art.arcane.gloss.GlossConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportSourceFilesTest {
    @TempDir
    Path root;

    @Test
    void ignoredEntriesCountBeforeSortingOrLoadingDocuments() throws IOException {
        Files.writeString(root.resolve("ignored.txt"), "ignored");
        Files.writeString(root.resolve(".hidden"), "ignored");
        ImportSourceFiles sources = new ImportSourceFiles(limits(32, 64, 8, 2, 4));
        IOException failure = assertThrows(IOException.class,
            () -> sources.collect(root, false, path -> path.toString().endsWith(".json")));
        assertTrue(failure.getMessage().contains("visited-entry"));
    }

    @Test
    void collectionsAndSingletonsShareOneSourceBudget() throws IOException {
        Path first = Files.createDirectory(root.resolve("first"));
        Path second = Files.createDirectory(root.resolve("second"));
        Files.writeString(first.resolve("a.json"), "{}");
        Files.writeString(second.resolve("b.json"), "{}");
        Path singleton = Files.writeString(root.resolve("settings.json"), "{}");
        ImportSourceFiles sources = new ImportSourceFiles(limits(32, 64, 2, 8, 4));
        assertEquals(1, sources.collect(first, true, path -> true).size());
        assertEquals(1, sources.collect(second, true, path -> true).size());
        assertThrows(IOException.class, () -> sources.single(singleton));
    }

    @Test
    void recursiveDepthRejectsEvenEmptyDirectoriesAndFlatScanStaysFlat() throws IOException {
        Files.createDirectories(root.resolve("one/two"));
        ImportSourceFiles recursive = new ImportSourceFiles(limits(32, 64, 8, 16, 1));
        IOException failure = assertThrows(IOException.class,
            () -> recursive.collect(root, true, path -> false));
        assertTrue(failure.getMessage().contains("directory-depth"));
        assertEquals(List.of(), new ImportSourceFiles(limits(32, 64, 8, 16, 1))
            .collect(root, false, path -> path.toString().endsWith(".json")));
    }

    @Test
    void selectedSymlinksAreRejectedAndIgnoredLinksAreNeverTraversed() throws IOException {
        Path directory = Files.createDirectory(root.resolve("collection"));
        Path external = Files.writeString(root.resolve("external.json"), "{}");
        Files.createSymbolicLink(directory.resolve("linked.json"), external);
        assertThrows(IOException.class, () -> new ImportSourceFiles(limits(32, 64, 8, 16, 1))
            .collect(directory, true, path -> path.toString().endsWith(".json")));
        Files.delete(directory.resolve("linked.json"));
        Files.createSymbolicLink(directory.resolve("ignored-link"), root);
        assertEquals(List.of(), new ImportSourceFiles(limits(32, 64, 8, 16, 1))
            .collect(directory, true, path -> path.toString().endsWith(".json")));
    }

    @Test
    void sourceReadsEnforceIndividualAndAggregateByteLimits() throws IOException {
        Path first = Files.write(root.resolve("a"), new byte[]{1, 2, 3, 4});
        Path second = Files.write(root.resolve("b"), new byte[]{5, 6, 7});
        ImportSourceFiles sources = new ImportSourceFiles(limits(4, 6, 8, 16, 1));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, sources.read(first));
        assertThrows(IOException.class, () -> sources.read(second));
        Files.write(first, new byte[5]);
        assertThrows(IOException.class,
            () -> new ImportSourceFiles(limits(4, 6, 8, 16, 1)).read(first));
    }

    private GlossConfig.Imports limits(int fileBytes, int totalBytes, int files, int visited, int depth) {
        return new GlossConfig.Imports(fileBytes, totalBytes, files, 600, 8, totalBytes, visited, depth);
    }
}
