package art.arcane.gloss.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentRegistryTest {
    private static final DocumentParser<String> PARSER = (fileName, raw) -> {
        String value = raw.trim();
        if (value.contains("bad")) {
            throw new IllegalArgumentException(fileName + " is broken");
        }
        return value;
    };

    @TempDir
    File folder;

    private DocumentRegistry<String> registry() {
        return DocumentRegistry.folder("test", folder, PARSER, value -> 1L);
    }

    private void write(String name, String content) throws IOException {
        File file = new File(folder, name);
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        assertTrue(file.setLastModified(file.lastModified() + 5000L));
    }

    @Test
    void reloadLoadsEveryDocumentAndPublishesSnapshot() throws IOException {
        write("alpha.json", "one");
        write("beta.json", "two");
        write("notes.txt", "ignored");
        DocumentRegistry<String> registry = registry();

        registry.reload();

        assertEquals(Set.of("alpha", "beta"), registry.ids());
        assertEquals("one", registry.get("alpha").value());
        assertEquals("two", registry.get("beta").value());
        assertEquals(DocumentHashes.sha256("one"), registry.get("alpha").contentHash());
        assertEquals(1L, registry.get("alpha").revision());
    }

    @Test
    void reloadDropsDocumentsWhoseFilesAreGone() throws IOException {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();

        assertTrue(new File(folder, "alpha.json").delete());
        registry.reload();

        assertEquals(Set.of(), registry.ids());
        assertNull(registry.get("alpha"));
    }

    @Test
    void parseFailureOnReloadKeepsTheLastGoodDocument() throws IOException {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();

        write("alpha.json", "bad content");
        registry.reload();

        assertEquals("one", registry.get("alpha").value());
    }

    @Test
    void pollReportsChangedCreatedAndDeletedDocuments() throws IOException {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();

        write("alpha.json", "one edited");
        write("beta.json", "two");
        DocumentDelta first = registry.poll();
        assertEquals(Set.of("alpha", "beta"), Set.copyOf(first.loaded()));
        assertEquals("one edited", registry.get("alpha").value());
        assertEquals("two", registry.get("beta").value());

        assertTrue(new File(folder, "beta.json").delete());
        DocumentDelta second = registry.poll();
        assertEquals(Set.of("beta"), Set.copyOf(second.removed()));
        assertNull(registry.get("beta"));
    }

    @Test
    void pollWithoutChangesIsEmpty() throws IOException {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();

        assertTrue(registry.poll().isEmpty());
    }

    @Test
    void pollParseFailureKeepsLastGoodAndReportsNothing() throws IOException {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = registry();
        registry.reload();

        write("alpha.json", "bad edit");

        assertTrue(registry.poll().isEmpty());
        assertEquals("one", registry.get("alpha").value());
    }

    @Test
    void ownWritesAreSuppressed() throws IOException {
        write("alpha.json", "one");
        DocumentRegistry<String> registry = DocumentRegistry.folder("test", folder, PARSER, value -> 1L,
            file -> file.getName().equals("alpha.json"));
        registry.reload();

        write("alpha.json", "one rewritten");

        assertTrue(registry.poll().isEmpty());
        assertEquals("one", registry.get("alpha").value());
    }

    @Test
    void singleFileModeLoadsChangesAndRemoves() throws IOException {
        File file = new File(folder, "solo.json");
        Files.writeString(file.toPath(), "first");
        DocumentRegistry<String> registry = DocumentRegistry.singleFile("test", file, PARSER, value -> 1L);

        registry.reload();
        assertEquals("first", registry.get("solo").value());

        Files.writeString(file.toPath(), "second value");
        assertTrue(file.setLastModified(file.lastModified() + 5000L));
        DocumentDelta changed = registry.poll();
        assertEquals(Set.of("solo"), Set.copyOf(changed.loaded()));
        assertEquals("second value", registry.get("solo").value());

        assertTrue(file.delete());
        DocumentDelta removed = registry.poll();
        assertEquals(Set.of("solo"), Set.copyOf(removed.removed()));
        assertNull(registry.get("solo"));
    }
}
