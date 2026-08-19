package art.arcane.gloss.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShippedDefaultsTest {
    private static final List<String> NAMES = List.of("alpha", "beta");

    @TempDir
    File folder;

    private ShippedDefaults defaults() {
        return new ShippedDefaults("testkind", folder, NAMES);
    }

    @Test
    void extractMissingWritesEveryAbsentDefault() {
        List<String> written = defaults().extractMissing();

        assertEquals(NAMES, written);
        assertTrue(new File(folder, "alpha.json").isFile());
        assertTrue(new File(folder, "beta.json").isFile());
    }

    @Test
    void extractMissingSkipsFilesAlreadyPresent() throws IOException {
        ShippedDefaults defaults = defaults();
        defaults.extractMissing();
        File alpha = new File(folder, "alpha.json");
        Files.writeString(alpha.toPath(), "user edit");

        List<String> written = defaults.extractMissing();

        assertEquals(List.of(), written);
        assertEquals("user edit", Files.readString(alpha.toPath()));
    }

    @Test
    void extractMissingRestoresOnlyDeletedFiles() {
        ShippedDefaults defaults = defaults();
        defaults.extractMissing();
        assertTrue(new File(folder, "beta.json").delete());

        List<String> written = defaults.extractMissing();

        assertEquals(List.of("beta"), written);
        assertTrue(new File(folder, "beta.json").isFile());
    }

    @Test
    void resetToDefaultOverwritesOneName() throws IOException {
        ShippedDefaults defaults = defaults();
        defaults.extractMissing();
        File alpha = new File(folder, "alpha.json");
        String original = Files.readString(alpha.toPath());
        Files.writeString(alpha.toPath(), "user edit");

        List<String> written = defaults.resetToDefault("alpha");

        assertEquals(List.of("alpha"), written);
        assertEquals(original, Files.readString(alpha.toPath()));
    }

    @Test
    void resetToDefaultAcceptsAFileNameWithExtension() {
        ShippedDefaults defaults = defaults();

        assertEquals(List.of("beta"), defaults.resetToDefault("beta.json"));
    }

    @Test
    void resetToDefaultStarOverwritesEverything() throws IOException {
        ShippedDefaults defaults = defaults();
        defaults.extractMissing();
        Files.writeString(new File(folder, "alpha.json").toPath(), "x");
        Files.writeString(new File(folder, "beta.json").toPath(), "y");

        List<String> written = defaults.resetToDefault(ShippedDefaults.ALL);

        assertEquals(NAMES, written);
    }

    @Test
    void unknownNamesWriteNothing() {
        assertEquals(List.of(), defaults().resetToDefault("gamma"));
    }
}
