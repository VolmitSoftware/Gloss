package art.arcane.gloss.panel;

import art.arcane.gloss.doc.DocumentPresetSource;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelPresetTest {
    @TempDir
    Path root;

    @Test
    void inheritedMenusReloadWithoutDocumentRevisionChangesAndSurviveMoves() throws Exception {
        PanelRepository repository = new PanelRepository(root);
        try {
            repository.load();
            PanelDefinition created = repository.create(PanelDefinition.create("display", "original",
                PanelTransform.at("example:world", UUID.randomUUID(), 0, 64, 0, 0)));
            Path file = root.resolve("panels/display.json");
            JsonObject source = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            source.remove("rootMenuId");
            source.addProperty("revision", created.revision() + 1L);
            Files.writeString(file, source.toString());
            writeCatalog("first");
            assertTrue(repository.load().successful());
            PanelDefinition first = repository.get("display").orElseThrow();
            assertEquals("first", first.rootMenuId());
            assertEquals(created.revision() + 1L, first.revision());
            writeCatalog("second");
            DocumentPresetSource presets = DocumentPresetSource.acquire(root);
            try {
                presets.refresh(true);
                repository.poll();
                repository.poll();
            } finally {
                DocumentPresetSource.release(presets);
            }
            PanelDefinition second = repository.get("display").orElseThrow();
            assertEquals("second", second.rootMenuId());
            PanelDefinition moved = repository.update(second.id(), second.revision(),
                current -> current.withTransform(PanelTransform.at("example:world", current.transform().worldUuid(), 5, 70, 5, 90)));
            JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            assertFalse(saved.has("rootMenuId"));
            assertEquals(3L, moved.revision());
            PanelDefinition renamed = repository.rename("display", "renamed", moved.revision());
            assertEquals("second", renamed.rootMenuId());
            assertFalse(JsonParser.parseString(Files.readString(root.resolve("panels/renamed.json")))
                .getAsJsonObject().has("rootMenuId"));
        } finally {
            repository.close();
        }
    }

    private void writeCatalog(String menu) throws Exception {
        Files.writeString(root.resolve("presets.json"), """
            {"schemaVersion":1,"revision":1,"defaults":{"panels":{"rootMenuId":"%s"}}}
            """.formatted(menu));
    }
}
