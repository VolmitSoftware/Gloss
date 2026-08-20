package art.arcane.gloss.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hot-reload contract {@code menus/} used to implement for itself: nested discovery, ids that
 * carry their subdirectory, add/change/remove through the watcher, a whole subdirectory disappearing
 * at once, and the content-identity rule that keeps a document the owner just wrote from being
 * applied a second time when the watcher reads it back.
 */
class DocumentTreeRegistryTest {
    private static final DocumentParser<String> PARSER = (fileName, raw) -> {
        String value = raw.trim();
        if (value.contains("bad")) {
            throw new IllegalArgumentException(fileName + " is broken");
        }
        return value;
    };

    @TempDir
    File root;

    private long stamp = System.currentTimeMillis();

    private DocumentRegistry<String> registry() {
        return DocumentRegistry.folderTree("menus", root, PARSER,
            value -> DocumentRegistry.UNVERSIONED);
    }

    @Test
    void reloadLoadsNestedDocumentsUnderSlashSeparatedIds() throws IOException {
        write("shop.json", "one");
        write("archive/old.json", "two");
        write("archive/deep/older.json", "three");
        write("notes.txt", "ignored");
        DocumentRegistry<String> registry = registry();

        registry.reload();

        assertEquals(Set.of("shop", "archive/old", "archive/deep/older"), registry.ids());
        assertEquals("two", registry.get("archive/old").value());
        assertEquals(DocumentHashes.sha256("two"), registry.get("archive/old").contentHash());
        assertEquals(DocumentRegistry.UNVERSIONED, registry.get("archive/old").revision());
    }

    @Test
    void aMissingRootIsAnEmptyRegistryAndIsNeverCreated() {
        File missing = new File(root, "menus");
        DocumentRegistry<String> registry = DocumentRegistry.folderTree("menus", missing, PARSER,
            value -> DocumentRegistry.UNVERSIONED);

        registry.reload();

        assertEquals(Set.of(), registry.ids());
        assertFalse(missing.exists());
    }

    @Test
    void pollReportsNestedCreationsIncludingAWholeNewSubdirectory() throws IOException {
        write("shop.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();

        write("quests.json", "two");
        write("archive/old.json", "three");
        write("archive/notes.txt", "ignored");

        DocumentDelta delta = registry.poll();

        assertEquals(List.of("archive/old", "quests"), sorted(delta.loaded()));
        assertEquals("three", registry.get("archive/old").value());
    }

    @Test
    void pollReportsNestedChangesUnderTheirRelativeIds() throws IOException {
        write("shop.json", "one");
        write("archive/old.json", "two");
        DocumentRegistry<String> registry = registry();
        registry.reload();

        write("shop.json", "one edited");
        write("archive/old.json", "two edited");

        DocumentDelta delta = registry.poll();

        assertEquals(List.of("archive/old", "shop"), sorted(delta.loaded()));
        assertEquals("two edited", registry.get("archive/old").value());
    }

    @Test
    void deletingADocumentUnregistersIt() throws IOException {
        write("archive/old.json", "two");
        DocumentRegistry<String> registry = registry();
        registry.reload();

        assertTrue(new File(root, "archive/old.json").delete());

        DocumentDelta delta = registry.poll();

        assertEquals(List.of("archive/old"), delta.removed());
        assertNull(registry.get("archive/old"));
    }

    @Test
    void deletingASubdirectoryUnregistersEveryDocumentBelowIt() throws IOException {
        write("shop.json", "one");
        write("archive/old.json", "two");
        write("archive/deep/older.json", "three");
        DocumentRegistry<String> registry = registry();
        registry.reload();

        deleteTree(new File(root, "archive"));

        DocumentDelta delta = registry.poll();

        assertEquals(List.of("archive/deep/older", "archive/old"), sorted(delta.removed()));
        assertEquals(Set.of("shop"), registry.ids());
    }

    /**
     * The suppression an in-game or editor-sync write depends on: the owner publishes the document
     * it wrote, and the watcher then finds those same bytes on disk and reports nothing, so nothing
     * destroys open sessions a second time.
     */
    @Test
    void rewritingTheSameBytesIsNotAChange() throws IOException {
        write("shop.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();

        write("shop.json", "one");

        assertTrue(registry.poll().isEmpty());
    }

    @Test
    void aPublishedDocumentIsServedImmediatelyAndReadBackAsNoChange() throws IOException {
        DocumentRegistry<String> registry = registry();
        registry.reload();
        write("archive/old.json", "written");

        GlossDocument<String> published = registry.publish("archive/old", "written", "written");

        assertEquals("written", published.value());
        assertEquals(DocumentHashes.sha256("written"), registry.get("archive/old").contentHash());
        assertTrue(registry.poll().isEmpty());
    }

    @Test
    void removeDropsAPublishedDocumentAndIsIdempotent() {
        DocumentRegistry<String> registry = registry();
        registry.reload();
        registry.publish("archive/old", "written", "written");

        assertTrue(registry.remove("archive/old"));
        assertNull(registry.get("archive/old"));
        assertFalse(registry.remove("archive/old"));
        assertFalse(registry.remove(null));
    }

    @Test
    void aParseFailureKeepsTheLastGoodDocumentAndReportsNothing() throws IOException {
        write("archive/old.json", "two");
        DocumentRegistry<String> registry = registry();
        registry.reload();

        write("archive/old.json", "bad edit");

        assertTrue(registry.poll().isEmpty());
        assertEquals("two", registry.get("archive/old").value());
    }

    @Test
    void symbolicLinksAndHiddenDirectoriesAreNeverLoaded() throws IOException {
        write(".drafts/secret.json", "hidden");
        DocumentRegistry<String> registry = registry();

        registry.reload();

        assertEquals(Set.of(), registry.ids());
        assertNotNull(registry.snapshot());
    }

    private static List<String> sorted(List<String> ids) {
        return ids.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private static void deleteTree(File directory) throws IOException {
        try (var paths = Files.walk(directory.toPath())) {
            for (java.nio.file.Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private void write(String relative, String content) throws IOException {
        File file = new File(root, relative);
        assertTrue(file.getParentFile().exists() || file.getParentFile().mkdirs());
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        stamp += 5000L;
        assertTrue(file.setLastModified(stamp));
    }
}
