package art.arcane.gloss.doc;

import art.arcane.gloss.inventory.InventoryDoc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentStoreTest {
    record TestDoc(long revision, String text) {
    }

    private static final DocumentReviser<TestDoc> REVISER = new DocumentReviser<>() {
        @Override
        public long revisionOf(TestDoc value) {
            return value.revision();
        }

        @Override
        public TestDoc withRevision(TestDoc value, long revision) {
            return new TestDoc(revision, value.text());
        }
    };

    @TempDir
    File folder;

    private DocumentStore<TestDoc> store() {
        return new DocumentStore<>("test", folder, REVISER);
    }

    @Test
    void savingPreparedDocumentKeepsInheritedFieldsAbsent() throws IOException {
        Path boards = Files.createDirectories(folder.toPath().resolve("boards"));
        Files.writeString(folder.toPath().resolve("presets.json"), """
            {"schemaVersion":1,"revision":1,"defaults":{"boards":{"text":"Inherited"}}}
            """);
        Files.writeString(boards.resolve("main.json"), "{\"revision\":1}");
        DocumentStore<TestDoc> store = new DocumentStore<>("boards", boards.toFile(), REVISER);
        store.write("main", new TestDoc(2L, "Inherited"));
        JsonObject unchanged = JsonParser.parseString(Files.readString(boards.resolve("main.json"))).getAsJsonObject();
        assertEquals(2L, unchanged.get("revision").getAsLong());
        assertFalse(unchanged.has("text"));
        store.write("main", new TestDoc(3L, "Local"));
        JsonObject changed = JsonParser.parseString(Files.readString(boards.resolve("main.json"))).getAsJsonObject();
        assertEquals("Local", changed.get("text").getAsString());
    }

    @Test
    void typedInventoryEditsPreserveNamedActionsAndCalls() throws IOException {
        Path inventories = Files.createDirectories(folder.toPath().resolve("inventories"));
        String source = """
            {"schemaVersion":1,"revision":1,"title":"Before","resolution":"9x1","mask":["AAAAAAAAA"],
             "actions":{"greet":[{"type":"message","message":"Hello"}]},
             "keys":{"A":{"type":"button","actions":[{"type":"call","action":"greet"}]}}}
            """;
        Path path = inventories.resolve("main.json");
        Files.writeString(path, source);
        DocumentStore<InventoryDoc> store = new DocumentStore<>("inventories", inventories.toFile(), new DocumentReviser<>() {
            @Override
            public long revisionOf(InventoryDoc value) {
                return value.revision();
            }

            @Override
            public InventoryDoc withRevision(InventoryDoc value, long revision) {
                JsonObject json = DocumentParsers.GSON.toJsonTree(value).getAsJsonObject();
                json.addProperty("revision", revision);
                return InventoryDoc.parse("main", json.toString());
            }
        });
        store.write("main", InventoryDoc.parse("main", source.replace("Before", "After")));
        JsonObject saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        assertEquals("After", saved.get("title").getAsString());
        assertEquals(JsonParser.parseString(source).getAsJsonObject().get("actions"), saved.get("actions"));
        assertEquals("call", saved.getAsJsonObject("keys").getAsJsonObject("A")
            .getAsJsonArray("actions").get(0).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void writeCreatesPrettyJsonWithTrailingNewline() throws IOException {
        DocumentStore<TestDoc> store = store();

        store.write("alpha", new TestDoc(1L, "hello"));

        File file = new File(folder, "alpha.json");
        assertTrue(file.isFile());
        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertTrue(content.endsWith(System.lineSeparator()));
        assertTrue(content.contains("\"revision\": 1"));
        assertTrue(content.contains("\"text\": \"hello\""));
    }

    @Test
    void writeLeavesNoTemporaryFilesBehind() throws IOException {
        DocumentStore<TestDoc> store = store();

        store.write("alpha", new TestDoc(1L, "hello"));

        List<String> names = List.of(folder.list());
        assertEquals(List.of("alpha.json"), names);
    }

    @Test
    void ownWriteIsDetectedUntilExternallyModified() throws IOException {
        DocumentStore<TestDoc> store = store();
        store.write("alpha", new TestDoc(1L, "hello"));
        File file = new File(folder, "alpha.json");

        assertTrue(store.isOwnWrite(file));

        Files.writeString(file.toPath(), "{\"revision\":9,\"text\":\"edited\"}");
        assertFalse(store.isOwnWrite(file));
    }

    @Test
    void oversizedOwnWriteCandidateIsRejectedBeforeHashingPastTheDocumentLimit() throws IOException {
        DocumentStore<TestDoc> store = store();
        store.write("alpha", new TestDoc(1L, "hello"));
        File file = new File(folder, "alpha.json");
        byte[] oversized = new byte[(int) DocumentRegistry.MAX_DOCUMENT_BYTES + 1];
        Files.write(file.toPath(), oversized);

        assertFalse(store.isOwnWrite(file));
    }

    @Test
    void unknownFileIsNotAnOwnWrite() {
        assertFalse(store().isOwnWrite(new File(folder, "never-written.json")));
        assertFalse(store().isOwnWrite(null));
    }

    @Test
    void rewriteUpdatesTheOwnWriteHash() throws IOException {
        DocumentStore<TestDoc> store = store();
        File file = new File(folder, "alpha.json");
        store.write("alpha", new TestDoc(1L, "one"));
        store.write("alpha", new TestDoc(2L, "two"));

        assertTrue(store.isOwnWrite(file));
        assertTrue(Files.readString(file.toPath()).contains("two"));
    }

    @Test
    void deleteRemovesFileAndForgetsHash() throws IOException {
        DocumentStore<TestDoc> store = store();
        store.write("alpha", new TestDoc(1L, "hello"));
        File file = new File(folder, "alpha.json");

        assertTrue(store.delete("alpha"));
        assertFalse(file.exists());
        assertFalse(store.delete("alpha"));

        Files.writeString(file.toPath(), "external");
        assertFalse(store.isOwnWrite(file));
    }

    @Test
    void mutateBumpsRevisionAndWrites() throws IOException {
        DocumentStore<TestDoc> store = store();
        TestDoc current = new TestDoc(3L, "old");

        TestDoc next = store.mutate("alpha", current, 3L, value -> new TestDoc(value.revision(), "new"));

        assertEquals(4L, next.revision());
        assertEquals("new", next.text());
        String content = Files.readString(new File(folder, "alpha.json").toPath());
        assertTrue(content.contains("\"revision\": 4"));
    }

    @Test
    void mutateWithStaleRevisionConflicts() {
        DocumentStore<TestDoc> store = store();
        TestDoc current = new TestDoc(3L, "old");

        DocumentRevisionConflictException failure = assertThrows(DocumentRevisionConflictException.class,
            () -> store.mutate("alpha", current, 2L, value -> value));

        assertEquals("alpha", failure.id());
        assertEquals("2", failure.expectedRevision());
        assertEquals("3", failure.actualRevision());
        assertFalse(new File(folder, "alpha.json").exists());
    }

    @Test
    void mutateAtMaxSafeRevisionOverflows() {
        DocumentStore<TestDoc> store = store();
        TestDoc current = new TestDoc(DocumentEnvelope.MAX_SAFE_REVISION, "old");

        assertThrows(IllegalStateException.class,
            () -> store.mutate("alpha", current, DocumentEnvelope.MAX_SAFE_REVISION, value -> value));
    }

    @Test
    void pathCharactersInIdsAreRejected() {
        DocumentStore<TestDoc> store = store();
        assertThrows(IllegalArgumentException.class, () -> store.fileFor("../escape"));
        assertThrows(IllegalArgumentException.class, () -> store.fileFor("a/b"));
        assertThrows(IllegalArgumentException.class, () -> store.fileFor("a\\b"));
        assertThrows(IllegalArgumentException.class, () -> store.fileFor("  "));
    }
}
