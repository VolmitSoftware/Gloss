package art.arcane.gloss.bubble;

import art.arcane.gloss.doc.ShippedDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BubbleLegacyDefaultUpgradeTest {
    private static final String LEGACY_DEFAULT = """
        {
          "schemaVersion": 1,
          "revision": 1,
          "prefix": "&7",
          "offset": [0.0, 1.0, 0.0],
          "wordWrapChars": 32,
          "lineStaggerTicks": 5,
          "maxAliveMs": 5000,
          "flyAway": true,
          "followPlayer": true,
          "hideOwn": true
        }
        """;

    @TempDir
    File folder;

    @Test
    void upgradesOnlyTheExactUntouchedShippedDefault() throws IOException {
        File file = new File(folder, "default.json");
        Files.writeString(file.toPath(), LEGACY_DEFAULT, StandardCharsets.UTF_8);

        assertTrue(ChatBubblesService.upgradeLegacyDefault(defaults()));

        BubbleStyleDoc upgraded = BubbleStyleDoc.parse(file.getName(), Files.readString(file.toPath()));
        assertEquals(BubbleStyleDoc.CURRENT_SCHEMA_VERSION, upgraded.schemaVersion());
        assertEquals(BubbleStyleDoc.DEFAULT_TRANSLATION_Y, upgraded.motion().translation().y());
    }

    @Test
    void preservesAnyEditedOrReformattedLegacyDefault() throws IOException {
        File file = new File(folder, "default.json");
        String edited = LEGACY_DEFAULT.replace("\"hideOwn\": true", "\"hideOwn\": false");
        Files.writeString(file.toPath(), edited, StandardCharsets.UTF_8);

        assertFalse(ChatBubblesService.upgradeLegacyDefault(defaults()));
        assertEquals(edited, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void upgradesTheUntouchedFastSchemaTwoDefault() throws IOException {
        String current = shippedDefault();
        String previous = previousHeight(current)
            .replace("\"spawnDelayMs\": 400", "\"spawnDelayMs\": 0");
        File file = new File(folder, "default.json");
        Files.writeString(file.toPath(), previous, StandardCharsets.UTF_8);

        assertTrue(ChatBubblesService.upgradeLegacyDefault(defaults()));
        assertEquals(current, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void upgradesTheUntouchedTwoToneSchemaTwoDefault() throws IOException {
        String current = shippedDefault();
        String previous = previousHeight(current)
            .replace("\"flyAway\": true", "\"flyAway\": false")
            .replace("\"durationMs\": 700", "\"durationMs\": 4233")
            .replace("\"spawnDelayMs\": 400", "\"spawnDelayMs\": 0")
            .replace(
            "    \"color\": \"#ffffff\",\n",
            "    \"color\": \"#ffffff\",\n    \"edgeColor\": \"#aaaaaa\",\n");
        File file = new File(folder, "default.json");
        Files.writeString(file.toPath(), previous, StandardCharsets.UTF_8);

        assertTrue(ChatBubblesService.upgradeLegacyDefault(defaults()));
        assertEquals(current, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void upgradesTheUntouchedLegacyCycleSchemaTwoDefault() throws IOException {
        String current = shippedDefault();
        String previous = previousHeight(current)
            .replace("\"flyAway\": true", "\"flyAway\": false")
            .replace("\"durationMs\": 700", "\"durationMs\": 4233")
            .replace("\"spawnDelayMs\": 400", "\"spawnDelayMs\": 0");
        File file = new File(folder, "default.json");
        Files.writeString(file.toPath(), previous, StandardCharsets.UTF_8);

        assertTrue(ChatBubblesService.upgradeLegacyDefault(defaults()));
        assertEquals(current, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void upgradesTheUntouchedPreviousHeightDefault() throws IOException {
        String current = shippedDefault();
        File file = new File(folder, "default.json");
        Files.writeString(file.toPath(), previousHeight(current), StandardCharsets.UTF_8);

        assertTrue(ChatBubblesService.upgradeLegacyDefault(defaults()));
        assertEquals(current, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    private String shippedDefault() throws IOException {
        return Files.readString(
            new File("src/main/resources/defaults/bubbles/default.json").toPath(),
            StandardCharsets.UTF_8);
    }

    private ShippedDefaults defaults() {
        return new ShippedDefaults(BubbleStyleDoc.KIND, folder, List.of("default"));
    }

    private static String previousHeight(String current) {
        return current.replace("\"offset\": [0.0, 0.3, 0.0]", "\"offset\": [0.0, 1.0, 0.0]");
    }
}
