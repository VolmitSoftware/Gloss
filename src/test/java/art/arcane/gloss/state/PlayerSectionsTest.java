package art.arcane.gloss.state;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class PlayerSectionsTest {
    @TempDir
    Path dataFolder;

    @Test
    void readsBackWhatItWrote() {
        PlayerSections sections = new PlayerSections(dataFolder);
        UUID player = UUID.randomUUID();

        sections.write(player, "markers", Map.of("home", List.of("world", 1.0D, 2.0D, 3.0D)));

        Map<String, Object> read = new PlayerSections(dataFolder).read(player, "markers");
        Assertions.assertEquals(List.of("world", 1.0D, 2.0D, 3.0D), read.get("home"));
    }

    @Test
    void keepsSectionsIndependent() {
        PlayerSections sections = new PlayerSections(dataFolder);
        UUID player = UUID.randomUUID();

        sections.write(player, "markers", Map.of("home", "a"));
        sections.write(player, "sky", Map.of("purpose", "arena"));

        Assertions.assertEquals(Map.of("home", "a"), sections.read(player, "markers"));
        Assertions.assertEquals(Map.of("purpose", "arena"), sections.read(player, "sky"));
    }

    @Test
    void returnsAnEmptyMapForAnAbsentPlayer() {
        Assertions.assertEquals(Map.of(), new PlayerSections(dataFolder).read(UUID.randomUUID(), "markers"));
    }

    @Test
    void overwritingASectionReplacesItAndLeavesOthersAlone() {
        PlayerSections sections = new PlayerSections(dataFolder);
        UUID player = UUID.randomUUID();
        sections.write(player, "markers", Map.of("home", "a", "mine", "b"));
        sections.write(player, "sky", Map.of("purpose", "arena"));

        sections.write(player, "markers", Map.of("home", "c"));

        Assertions.assertEquals(Map.of("home", "c"), sections.read(player, "markers"));
        Assertions.assertEquals(Map.of("purpose", "arena"), sections.read(player, "sky"));
    }

    @Test
    void quarantinesACorruptFileAndReadsEmpty() throws IOException {
        UUID player = UUID.randomUUID();
        Path file = dataFolder.resolve("state").resolve("players").resolve(player + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);
        PlayerSections sections = new PlayerSections(dataFolder);

        Assertions.assertEquals(Map.of(), sections.read(player, "markers"));

        try (var quarantined = Files.list(file.getParent())) {
            Assertions.assertTrue(quarantined.anyMatch(path -> path.getFileName().toString()
                .startsWith(player + ".json.corrupt-")));
        }
        Assertions.assertFalse(Files.exists(file));
    }

    @Test
    void forgetRemovesTheFile() {
        PlayerSections sections = new PlayerSections(dataFolder);
        UUID player = UUID.randomUUID();
        sections.write(player, "markers", Map.of("home", "a"));

        sections.forget(player);

        Assertions.assertEquals(Map.of(), sections.read(player, "markers"));
    }

    @Test
    void readsAfterTheFirstAreServedFromMemory() throws IOException {
        PlayerSections sections = new PlayerSections(dataFolder);
        UUID player = UUID.randomUUID();
        sections.write(player, "preferences", Map.of("arena", Boolean.TRUE));
        Assertions.assertEquals(Map.of("arena", Boolean.TRUE), sections.read(player, "preferences"));
        Files.delete(dataFolder.resolve("state").resolve("players").resolve(player + ".json"));

        Assertions.assertEquals(Map.of("arena", Boolean.TRUE), sections.read(player, "preferences"),
            "the render path must not re-read and re-parse the player file on every call");
    }

    @Test
    void aWriteDoesNotReReadTheFileItAlreadyHolds() throws IOException {
        PlayerSections sections = new PlayerSections(dataFolder);
        UUID player = UUID.randomUUID();
        sections.write(player, "preferences", Map.of("arena", Boolean.TRUE));
        Path file = dataFolder.resolve("state").resolve("players").resolve(player + ".json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        sections.write(player, "sky", Map.of("purpose", "arena"));

        Assertions.assertEquals(Map.of("arena", Boolean.TRUE), sections.read(player, "preferences"));
        Assertions.assertEquals(Map.of("purpose", "arena"), sections.read(player, "sky"));
    }

    @Test
    void writingTheSameValuesAgainDoesNotTouchTheDisk() throws IOException {
        PlayerSections sections = new PlayerSections(dataFolder);
        UUID player = UUID.randomUUID();
        sections.write(player, "sky", Map.of("purpose", "arena"));
        Path file = dataFolder.resolve("state").resolve("players").resolve(player + ".json");
        Files.delete(file);

        sections.write(player, "sky", Map.of("purpose", "arena"));

        Assertions.assertFalse(Files.exists(file),
            "an unchanged section must not cost a serialize and an atomic replace");
    }

    @Test
    void clearingASectionThatWasNeverSetDoesNotTouchTheDisk() {
        PlayerSections sections = new PlayerSections(dataFolder);
        UUID player = UUID.randomUUID();

        sections.write(player, "camera", Map.of());

        Assertions.assertFalse(Files.exists(
            dataFolder.resolve("state").resolve("players").resolve(player + ".json")));
    }

    @Test
    void evictingAPlayerDropsTheCacheAndKeepsTheFile() {
        PlayerSections sections = new PlayerSections(dataFolder);
        UUID player = UUID.randomUUID();
        sections.write(player, "markers", Map.of("home", "a"));

        sections.evict(player);

        Assertions.assertTrue(Files.exists(
            dataFolder.resolve("state").resolve("players").resolve(player + ".json")));
        Assertions.assertEquals(Map.of("home", "a"), sections.read(player, "markers"));
    }
}
