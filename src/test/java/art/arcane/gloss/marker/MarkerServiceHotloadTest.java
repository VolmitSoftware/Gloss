package art.arcane.gloss.marker;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.state.PlayerSections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkerServiceHotloadTest {
    @TempDir
    Path folder;

    @Test
    void firstHotloadUsesThePendingDocumentBeforeRegistryCommit() throws Exception {
        Gloss plugin = CharacterizationSupport.bareGloss(CharacterizationSupport.server(Map.of()));
        CharacterizationSupport.setField(plugin, "dataFolder", folder.toFile());
        MarkerService service = new MarkerService(plugin, new PlayerSections(folder));
        DocumentRegistry<?> registry = (DocumentRegistry<?>) CharacterizationSupport.getField(service, "registry");
        Path markers = Files.createDirectories(folder.resolve("markers"));
        registry.reload();
        try {
            Files.writeString(markers.resolve("test.json"), """
                {"schemaVersion":1,"revision":1,"anchor":{"world":"world","x":1,"y":70,"z":1},
                 "label":"Marker","icon":{"type":"text","text":"Icon"},"lifetimeTicks":100}
                """);
            MarkerDoc.parse("test.json", Files.readString(markers.resolve("test.json")));
            CharacterizationSupport.setField(registry, "nextFullWatchScanNanos", 0L);
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (registry.ids().isEmpty() && System.nanoTime() < deadline) {
                CharacterizationSupport.invoke(service, "poll", new Class<?>[0]);
                if (registry.ids().isEmpty()) {
                    Thread.sleep(10L);
                }
            }
            assertEquals(1, registry.ids().size());
            List<?> documents = (List<?>) CharacterizationSupport.getField(service, "documents");
            assertEquals(1, documents.size());
            MarkerRuntime runtime = (MarkerRuntime) documents.getFirst();
            assertEquals("Marker", runtime.spec().label());
            assertEquals(100L, runtime.spec().lifetimeTicks());
        } finally {
            registry.close();
        }
    }
}
