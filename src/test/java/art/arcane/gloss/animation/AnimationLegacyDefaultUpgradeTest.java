package art.arcane.gloss.animation;

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

class AnimationLegacyDefaultUpgradeTest {
    private static final String LEGACY_DEFAULT = """
        {
          "schemaVersion": 1,
          "revision": 1,
          "mode": "ascend",
          "frameIntervalMs": 500,
          "frames": [
            "&cGloss",
            "&6Gloss",
            "&aGloss",
            "&bGloss"
          ]
        }
        """;

    @TempDir
    File folder;

    @Test
    void upgradesOnlyTheExactLegacyShippedRainbow() throws IOException {
        File file = new File(folder, "rainbow.json");
        Files.writeString(file.toPath(), LEGACY_DEFAULT, StandardCharsets.UTF_8);

        assertTrue(AnimationService.upgradeLegacyRainbowDefault(defaults()));

        String upgraded = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        AnimationDoc doc = AnimationDoc.parse(file.getName(), upgraded);
        assertEquals(List.of("&c", "&6", "&a", "&b"), doc.frames());
        assertEquals("&c&lONLINE", doc.frames().getFirst() + "&lONLINE");
        assertFalse((doc.frames().getFirst() + "&lONLINE").contains("GlossONLINE"));
    }

    @Test
    void preservesAnyUserEditedRainbow() throws IOException {
        File file = new File(folder, "rainbow.json");
        String edited = LEGACY_DEFAULT.replace("&bGloss", "&bCustom");
        Files.writeString(file.toPath(), edited, StandardCharsets.UTF_8);

        assertFalse(AnimationService.upgradeLegacyRainbowDefault(defaults()));
        assertEquals(edited, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void preservesFormattingVariantsOfTheLegacyRainbow() throws IOException {
        File file = new File(folder, "rainbow.json");
        String reformatted = LEGACY_DEFAULT.replace("  \"revision\"", "    \"revision\"");
        Files.writeString(file.toPath(), reformatted, StandardCharsets.UTF_8);

        assertFalse(AnimationService.upgradeLegacyRainbowDefault(defaults()));
        assertEquals(reformatted, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    private ShippedDefaults defaults() {
        return new ShippedDefaults(AnimationDoc.KIND, folder, List.of("rainbow"));
    }
}
