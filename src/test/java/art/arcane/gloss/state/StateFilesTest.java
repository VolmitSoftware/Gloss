package art.arcane.gloss.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateFilesTest {
    @TempDir
    Path folder;

    @Test
    void writeThenReadRoundTripsNumbersStringsBooleansAndSections() throws IOException {
        Path file = folder.resolve("players").resolve("one.json");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("visits", 3.0D);
        values.put("event", "none");
        values.put("welcomed", true);
        values.put("markers", Map.of("home", Map.of("x", 1.5D)));

        StateFiles.write(file, values);
        Map<String, Object> read = StateFiles.read(file);

        assertEquals(3.0D, read.get("visits"));
        assertEquals("none", read.get("event"));
        assertEquals(true, read.get("welcomed"));
        assertEquals(1.5D, ((Map<?, ?>) ((Map<?, ?>) read.get("markers")).get("home")).get("x"));
        assertTrue(Files.isDirectory(folder.resolve("players")));
    }

    @Test
    void missingFileReadsAsEmptyWithoutCreatingAnything() throws IOException {
        Map<String, Object> read = StateFiles.read(folder.resolve("players").resolve("missing.json"));

        assertTrue(read.isEmpty());
        assertFalse(Files.exists(folder.resolve("players")));
    }

    @Test
    void corruptFileIsSetAsideAndReadsAsEmpty() throws IOException {
        Path file = folder.resolve("global.json");
        Files.writeString(file, "{ not json", StandardCharsets.UTF_8);

        Map<String, Object> read = StateFiles.read(file);

        assertTrue(read.isEmpty());
        assertFalse(Files.exists(file));
        try (Stream<Path> listing = Files.list(folder)) {
            List<String> names = listing.map(path -> path.getFileName().toString()).toList();
            assertEquals(1, names.size());
            assertTrue(names.getFirst().startsWith("global.json.corrupt-"), names.toString());
        }
    }

    @Test
    void nonObjectJsonCountsAsCorrupt() throws IOException {
        Path file = folder.resolve("global.json");
        Files.writeString(file, "[1, 2]", StandardCharsets.UTF_8);

        assertTrue(StateFiles.read(file).isEmpty());
        assertFalse(Files.exists(file));
    }
}
