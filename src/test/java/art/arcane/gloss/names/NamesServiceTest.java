package art.arcane.gloss.names;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DataWatchdog;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.drop.DropNameFormatter;
import art.arcane.gloss.expr.ExprFunctionRegistry;
import art.arcane.gloss.menu.CharacterizationSupport;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamesServiceTest {
    @TempDir
    Path folder;

    @Test
    void hotloadReplacesCachedNamesAcrossDropLabelsAndExpressions() throws Exception {
        Server server = CharacterizationSupport.server(Map.of());
        Object previousServer = CharacterizationSupport.installServer(server);
        Gloss plugin = CharacterizationSupport.bareGloss(server);
        Gloss previousGloss = CharacterizationSupport.installGloss(plugin);
        CharacterizationSupport.setField(plugin, "dataFolder", folder.toFile());
        CharacterizationSupport.setField(plugin, "watchdog", new DataWatchdog(plugin));
        NamesService names = new NamesService(plugin);
        CharacterizationSupport.setField(plugin, "names", names);
        try {
            names.enable();
            assertTrue(Files.exists(folder.resolve("names.json")));
            assertEquals("Oak Log", DropNameFormatter.materialName("OAK_LOG"));
            Files.writeString(folder.resolve("names.json"), """
                {"schemaVersion":1,"revision":2,"materials":{"oak_log":"Timber"}}
                """);
            DocumentRegistry<?> registry = names.registries().get(NamesDoc.KIND);
            CharacterizationSupport.setField(registry, "nextContentReconciliationNanos", 0L);
            DocumentDelta delta = registry.poll();
            assertTrue(!delta.isEmpty());
            assertTrue(registry.apply(delta, () -> names.apply(delta)));
            assertEquals("Timber", DropNameFormatter.materialName("OAK_LOG"));
            assertEquals("Local timber", DropNameFormatter.typeName(Map.of("OAK_LOG", "Local timber"), "OAK_LOG"));
            assertEquals("Timber", ExprFunctionRegistry.global().call(null, "name", List.of("materials", "OAK_LOG")));
            Files.writeString(folder.resolve("names.json"), """
                {"schemaVersion":1,"revision":3,"materials":{"oak_log":42}}
                """);
            CharacterizationSupport.setField(registry, "nextContentReconciliationNanos", 0L);
            assertTrue(registry.poll().isEmpty());
            assertEquals("Timber", DropNameFormatter.materialName("OAK_LOG"));
            assertEquals(2L, registry.get(NamesDoc.KIND).revision());
        } finally {
            names.disable();
            CharacterizationSupport.restoreGloss(previousGloss);
            CharacterizationSupport.restoreServer(previousServer);
        }
    }
}
