package art.arcane.gloss.doc;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a tree-shaped document folder hands to the parser, at boot and on every watcher pass. The
 * scan and the watcher share one predicate and one canonical relative-id rule; {@code menus/} is the
 * kind that uses them.
 */
class DocumentTreeTest {

    @TempDir
    File temp;

    private long stamp = System.currentTimeMillis();

    @Test
    void discoveryLoadsNestedJsonWithCanonicalSlashIds() throws IOException {
        File menus = folder("menus");
        write(new File(menus, "shop.json"), "{}");
        write(new File(menus, "notes.txt"), "not json");
        File nested = new File(menus, "archive");
        assertTrue(nested.mkdirs());
        write(new File(nested, "old.json"), "{}");

        assertEquals(List.of("archive/old", "shop"), ids(menus, DocumentTree.discover(menus)));
    }

    @Test
    void discoveryOfACreatedSubdirectoryReachesTheFilesItArrivedWith() throws IOException {
        File menus = folder("menus");
        File nested = new File(menus, "archive/deep");
        assertTrue(nested.mkdirs());
        write(new File(nested, "old.json"), "{}");
        write(new File(nested, "notes.txt"), "not json");

        assertEquals(List.of("archive/deep/old"),
            ids(menus, DocumentTree.discover(menus, new File(menus, "archive"))));
    }

    @Test
    void missingRootsAndMissingStartsDiscoverNothing() {
        File menus = new File(temp, "menus");

        assertEquals(List.of(), DocumentTree.discover(menus));
        assertEquals(List.of(), DocumentTree.discover(menus, new File(menus, "archive")));
        assertEquals(List.of(), DocumentTree.discover(null, null));
    }

    @Test
    void extensionMatchIsCaseInsensitive() throws IOException {
        File menus = folder("menus");
        File upper = new File(menus, "Shop.JSON");
        write(upper, "{}");

        assertTrue(DocumentTree.isDocument(menus, upper));
        assertEquals("Shop", DocumentTree.idOf(menus, upper));
        assertFalse(DocumentTree.isDocument(menus, new File(menus, "shop.json.bak")));
        assertFalse(DocumentTree.isDocument(menus, menus));
        assertFalse(DocumentTree.isDocument(menus, null));
        assertFalse(DocumentTree.isDocument(null, upper));
    }

    @Test
    void hiddenFoldersAndFilesOutsideTheRootAreRejected() throws IOException {
        File menus = folder("menus");
        File hidden = new File(menus, ".drafts/secret.json");
        assertTrue(hidden.getParentFile().mkdirs());
        write(hidden, "{}");
        File outside = new File(temp, "outside.json");
        write(outside, "{}");

        assertFalse(DocumentTree.isDocument(menus, hidden));
        assertFalse(DocumentTree.isDocument(menus, outside));
        assertTrue(DocumentTree.discover(menus).isEmpty());
    }

    @Test
    void symbolicLinkFilesAndDirectoriesAreNeverDiscovered() throws IOException {
        File menus = folder("menus");
        File outsideDirectory = folder("outside-menus");
        File outsideMenu = new File(outsideDirectory, "outside.json");
        write(outsideMenu, "{}");
        link(new File(menus, "linked.json").toPath(), outsideMenu.toPath());
        link(new File(menus, "linked-directory").toPath(), outsideDirectory.toPath());

        assertFalse(DocumentTree.isDocument(menus, new File(menus, "linked.json")));
        assertFalse(DocumentTree.isDocument(menus, new File(menus, "linked-directory/outside.json")));
        assertTrue(DocumentTree.discover(menus).isEmpty());
    }

    @Test
    void aDeletedPathStillResolvesToTheIdThatHasToBeUnregistered() throws IOException {
        File menus = folder("menus");
        File gone = new File(menus, "archive/old.json");

        assertTrue(DocumentTree.isDocument(menus, gone));
        assertEquals("archive/old", DocumentTree.idOf(menus, gone));
    }

    @Test
    void directoryPrefixesAreRelativeToTheRootAndNeverEscapeIt() throws IOException {
        File menus = folder("menus");
        File nested = new File(menus, "archive/deep");
        assertTrue(nested.mkdirs());

        assertEquals("archive/deep", DocumentTree.prefixOf(menus, nested));
        assertNull(DocumentTree.prefixOf(menus, menus));
        assertNull(DocumentTree.prefixOf(menus, temp));
    }

    /**
     * A file named like a directory never contributes a prefix that could unregister the documents
     * of a real directory beside it: deleting {@code archive.json} must not take {@code archive/}
     * with it.
     */
    @Test
    void aDocumentPrefixKeepsItsExtension() throws IOException {
        File menus = folder("menus");

        assertEquals("archive.json", DocumentTree.prefixOf(menus, new File(menus, "archive.json")));
    }

    private static List<String> ids(File root, List<File> candidates) {
        List<String> names = new ArrayList<>();
        for (File candidate : candidates) {
            names.add(DocumentTree.idOf(root, candidate));
        }
        return names;
    }

    private File folder(String name) {
        File created = new File(temp, name);
        assertTrue(created.mkdirs());
        return created;
    }

    private static void link(Path link, Path target) throws IOException {
        assertTrue(Files.isDirectory(link.getParent()));
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException unsupported) {
            Assumptions.abort("symbolic links are unavailable here: " + unsupported.getMessage());
        }
    }

    private void write(File file, String content) throws IOException {
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        stamp += 5000L;
        assertTrue(file.setLastModified(stamp));
    }
}
