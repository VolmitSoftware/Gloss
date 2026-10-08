package art.arcane.gloss.doc;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentPresetRegistryTest {
    @TempDir
    Path root;

    @Test
    void reloadChangesPreparedValueWithoutChangingAuthoredSourceOrRevision() throws Exception {
        Path boards = Files.createDirectories(root.resolve("boards"));
        String source = "{\"schemaVersion\":2,\"revision\":7,\"preset\":\"compact\"}";
        Files.writeString(boards.resolve("default.json"), source);
        writePreset("First");
        try (DocumentRegistry<String> registry = registry(boards)) {
            registry.reload();
            assertEquals("First", registry.get("default").value());
            assertEquals(source, registry.get("default").raw());
            writePreset("Second");
            registry.reload();
            assertEquals("Second", registry.get("default").value());
            assertEquals(source, Files.readString(boards.resolve("default.json")));
            assertEquals(7L, registry.get("default").revision());
        }
    }

    @Test
    void invalidCatalogRetainsLiveValueAndCanRecover() throws Exception {
        Path boards = Files.createDirectories(root.resolve("boards"));
        Files.writeString(boards.resolve("default.json"), "{\"preset\":\"compact\"}");
        writePreset("Before");
        try (DocumentRegistry<String> registry = registry(boards)) {
            registry.reload();
            Files.writeString(root.resolve("presets.json"), "invalid");
            registry.reload();
            assertEquals("Before", registry.get("default").value());
            writePreset("After");
            registry.reload();
            assertEquals("After", registry.get("default").value());
        }
    }

    @Test
    void deletingCatalogRestoresDocumentDefaults() throws Exception {
        Path boards = Files.createDirectories(root.resolve("boards"));
        Files.writeString(boards.resolve("default.json"), "{}");
        Files.writeString(root.resolve("presets.json"), """
            {"schemaVersion":1,"revision":1,"defaults":{"boards":{"title":"Global"}}}
            """);
        try (DocumentRegistry<String> registry = registry(boards)) {
            registry.reload();
            assertEquals("Global", registry.get("default").value());
            Files.delete(root.resolve("presets.json"));
            registry.reload();
            assertEquals("Local default", registry.get("default").value());
            assertTrue(registry.poll().loaded().isEmpty());
        }
    }

    private DocumentRegistry<String> registry(Path boards) {
        return DocumentRegistry.folder("boards", boards.toFile(), (name, source) -> {
            return JsonParser.parseString(source).getAsJsonObject().has("title")
                ? JsonParser.parseString(source).getAsJsonObject().get("title").getAsString()
                : "Local default";
        }, value -> 7L);
    }

    private void writePreset(String title) throws Exception {
        Files.writeString(root.resolve("presets.json"), """
            {"schemaVersion":1,"revision":1,"presets":{"boards":{"compact":{"values":{"title":"%s"}}}}}
            """.formatted(title));
    }
}
